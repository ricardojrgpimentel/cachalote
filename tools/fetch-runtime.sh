#!/bin/bash
# fetch-runtime.sh — obtém o runtime Node para **Android** (Termux) sem depender de um
# telemóvel: descarrega os pacotes .deb do repositório do Termux e monta a árvore que o daemon
# e a app esperam.
#
#   ./tools/fetch-runtime.sh                     # descarrega do repositório Termux (arm64)
#   ./tools/fetch-runtime.sh --arch x86_64       # para o emulador x86_64 (ver em baixo)
#   ./tools/fetch-runtime.sh --dir DIR           # copia de uma árvore já existente
#   ./tools/fetch-runtime.sh --from-device       # tira do Termux de um telemóvel ligado
#   ./tools/fetch-runtime.sh --out DIR           # onde montar (por omissão .work/node-android)
#
# Saída: <out>/root/data/data/com.termux/files/usr/{bin/node,lib/*.so*}
#
# Porque é que não se usa um Node oficial: os binários oficiais para Linux são **glibc** e o
# Android é **bionic**. O Node do Termux é compilado para bionic e é o que o DSH usa. Ver a base
# de conhecimento (itens 4 e 61).
#
# --arch serve o emulador: o telefone é arm64, mas um AVD x86_64 corre num portátil x86_64 e
# não consegue executar um binário arm64. O Termux publica as mesmas versões para as duas
# arquitecturas, por isso o caminho é o mesmo — só muda o índice do repositório.
set -euo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
REPO="https://packages.termux.dev/apt/termux-main"
TERMUX_PREFIX_IN_DEB="data/data/com.termux/files/usr"
ARCH="aarch64"
OUT_OVERRIDE=""

MODE="download"
DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; MODE="dir"; shift 2 ;;
    --from-device) MODE="device"; shift ;;
    --arch) ARCH="$2"; shift 2 ;;
    --out) OUT_OVERRIDE="$2"; shift 2 ;;
    -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

# As arquitecturas que o Termux publica. Falhar cedo e com a lista à vista é melhor do que
# montar uma árvore vazia por causa de um nome mal escrito.
case "$ARCH" in
  aarch64|arm|x86_64|i686) ;;
  *) echo "ERRO: --arch '$ARCH' desconhecida (aarch64, arm, x86_64, i686)" >&2; exit 2 ;;
esac
OUT="${OUT_OVERRIDE:-$WS/.work/node-android}"

if [ "$MODE" = "dir" ]; then
  [ -d "$DIR/bin" ] || { echo "ERRO: $DIR não tem bin/"; exit 1; }
  echo "== a copiar runtime de $DIR =="
  rm -rf "$OUT"; mkdir -p "$OUT/root/$TERMUX_PREFIX_IN_DEB"
  cp -R "$DIR/." "$OUT/root/$TERMUX_PREFIX_IN_DEB/"
elif [ "$MODE" = "device" ]; then
  echo "== a tirar o Node do Termux do telemóvel $DEV =="
  command -v adb >/dev/null || { echo "ERRO: preciso do adb"; exit 1; }
  rm -rf "$OUT"; mkdir -p "$OUT/root/$TERMUX_PREFIX_IN_DEB"
  PREFIX="/data/data/com.termux/files/usr"
  adb -s "$DEV" shell "su -c 'cd $PREFIX && tar -cf /data/local/tmp/termux-node.tar bin/node lib/libcares.so lib/libcrypto.so.3 lib/libssl.so.3 lib/libz.so.1 lib/libsqlite3.so lib/libicudata.so.78 lib/libicui18n.so.78 lib/libicuuc.so.78 lib/libc++_shared.so 2>/dev/null || tar -cf /data/local/tmp/termux-node.tar bin/node lib/*.so*'"
  adb -s "$DEV" pull /data/local/tmp/termux-node.tar "$OUT/termux-node.tar" >/dev/null
  tar -xf "$OUT/termux-node.tar" -C "$OUT/root/$TERMUX_PREFIX_IN_DEB"
  rm -f "$OUT/termux-node.tar"
else
  echo "== a descarregar os pacotes do Termux ($ARCH) =="
  command -v python3 >/dev/null || { echo "ERRO: preciso do python3 para extrair os .deb"; exit 1; }
  # O Node e as bibliotecas que ele pede (DT_NEEDED). Os nomes dos ficheiros são procurados
  # no índice do repositório, para não ficarem presos a versões.
  PKGS=(nodejs-lts openssl zlib libicu c-ares libsqlite libc++)
  rm -rf "$OUT"; mkdir -p "$OUT/debs" "$OUT/root"
  echo "   (a ler o índice do repositório)"
  # Índice num ficheiro: passar 5 MB por um pipe 7 vezes, com o awk a sair cedo, dá SIGPIPE
  # (e com pipefail isso mata o script — foi o que aconteceu à primeira).
  curl -fsSL "$REPO/dists/stable/main/binary-$ARCH/Packages.gz" | gunzip > "$OUT/Packages"
  for pkg in "${PKGS[@]}"; do
    file="$(awk -v pkg="$pkg" 'BEGIN{RS="";FS="\n"} {p="";f="";for(i=1;i<=NF;i++){if($i~/^Package: /)p=substr($i,10); if($i~/^Filename: /)f=substr($i,11)} if(p==pkg && f!=""){print f; exit}}' "$OUT/Packages")"
    if [ -z "$file" ]; then
      echo "   --  $pkg (não está no índice; o fecho de DT_NEEDED diz se faz falta)"
      continue
    fi
    if curl -fsSL -o "$OUT/debs/$(basename "$file")" "$REPO/$file"; then
      echo "   ok  $(basename "$file")"
    else
      echo "   --  $pkg (falhou a descarga)"
      rm -f "$OUT/debs/$(basename "$file")"
    fi
  done
  python3 - "$OUT" "$TERMUX_PREFIX_IN_DEB" <<'PYEXTRACT'
import os, sys, tarfile, lzma, gzip, io
out, prefix = sys.argv[1], sys.argv[2]
root = os.path.join(out, "root")
def ar_members(path):
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise SystemExit(f"{path}: não é um arquivo ar (deb)")
        while True:
            header = f.read(60)
            if len(header) < 60:
                return
            name = header[0:16].decode().strip().rstrip("/")
            size = int(header[48:58].decode().strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            yield name, data
for deb in sorted(os.listdir(os.path.join(out, "debs"))):
    path = os.path.join(out, "debs", deb)
    for name, data in ar_members(path):
        if not name.startswith("data.tar"):
            continue
        if name.endswith(".xz"):
            raw = lzma.decompress(data)
        elif name.endswith(".gz"):
            raw = gzip.decompress(data)
        elif name.endswith(".zst"):
            raise SystemExit("data.tar.zst: sem suporte (instala zstd)")
        else:
            raw = data
        with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
            for member in tar.getmembers():
                name = member.name[2:] if member.name.startswith("./") else member.name
                member.name = name
                if not name.startswith(prefix + "/"):
                    continue
                tar.extract(member, root, filter="data")
        print(f"   extraído {deb}")
PYEXTRACT
  # O `node` do Termux vem com o caminho do prefixo gravado; nada a fazer (o daemon define
  # LD_LIBRARY_PATH e o binário é relativo à sua própria árvore).
  chmod +x "$OUT/root/$TERMUX_PREFIX_IN_DEB/bin/node" 2>/dev/null || true
fi

NODE="$OUT/root/$TERMUX_PREFIX_IN_DEB/bin/node"
[ -f "$NODE" ] || { echo "ERRO: não encontrei bin/node no runtime montado"; exit 1; }
echo "== runtime pronto ($ARCH): $(du -sh "$OUT/root" | cut -f1) =="
ls "$OUT/root/$TERMUX_PREFIX_IN_DEB/bin" "$OUT/root/$TERMUX_PREFIX_IN_DEB/lib" 2>/dev/null | head -14
# Conferir a arquitectura: um runtime arm64 num emulador x86_64 falha de forma opaca
# ("cannot execute binary file"), e é barato apanhar isso aqui em vez de no dispositivo.
if command -v file >/dev/null 2>&1; then
  echo "arquitectura do node: $(file -b "$NODE" | cut -d, -f1-2)"
fi
# `shasum` é do Perl (macOS traz sempre; Ubuntu tem o pacote perl). `sha256sum` é do coreutils.
if command -v sha256sum >/dev/null 2>&1; then
  NODE_SHA="$(sha256sum "$NODE" | cut -c1-32)"
else
  NODE_SHA="$(shasum -a 256 "$NODE" | cut -c1-32)"
fi
echo "sha256 do node: ${NODE_SHA}…"
