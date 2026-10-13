#!/bin/bash
# prepare-embed.sh — prepara o runtime Node para ser embutido na APK como biblioteca nativa.
#
#   ./tools/prepare-embed.sh                 # arm64 (telefone)
#   ./tools/prepare-embed.sh --arch x86_64   # emulador x86_64
#
# Porquê: a partir do targetSdk 29 o directório privado da app é noexec, mas o
# `nativeLibraryDir` (onde a APK extrai os jniLibs) continua executável. Renomear o node
# para `libnode.so` e pô-lo em jniLibs é o que permite ao flavor "in-app" (sem root nem
# Shizuku) correr o motor a partir da própria app.
#
# A arquitectura tem de casar com a do dispositivo: um `libnode.so` arm64 num emulador x86_64
# instala-se e depois falha ao carregar. O ABI (nome da pasta em jniLibs) não é o mesmo que o
# nome da arquitectura no Termux — daí a tabela.
#
# Só se copiam as bibliotecas que o node realmente pede (fecho de DT_NEEDED), com o nome
# exacto do soname — os pacotes do Termux trazem o mesmo ficheiro com 3 nomes, o que
# triplicaria o tamanho da APK à toa.
set -euo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
ARCH="aarch64"
while [ $# -gt 0 ]; do
  case "$1" in
    --arch) ARCH="$2"; shift 2 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done
case "$ARCH" in
  aarch64) ABI="arm64-v8a"; SUFFIX="" ;;
  x86_64)  ABI="x86_64";    SUFFIX="-x86_64" ;;
  arm)     ABI="armeabi-v7a"; SUFFIX="-arm" ;;
  i686)    ABI="x86";       SUFFIX="-i686" ;;
  *) echo "ERRO: --arch '$ARCH' desconhecida (aarch64, arm, x86_64, i686)" >&2; exit 2 ;;
esac
SRC="${CACHALOTE_RUNTIME_SRC:-$WS/.work/node-android$SUFFIX/root/data/data/com.termux/files/usr}"
DST="$WS/.work/jniLibs/$ABI"

[ -x "$SRC/bin/node" ] || { echo "ERRO: falta $SRC/bin/node (corre tools/fetch-runtime.sh)"; exit 1; }
echo "== runtime embutido: $ARCH ($ABI) =="
rm -rf "$WS/.work/jniLibs"; mkdir -p "$DST"
cp -f "$SRC/bin/node" "$DST/libnode.so"; chmod 755 "$DST/libnode.so"

python3 - "$SRC" "$DST" <<'PY'
import os, struct, sys, shutil
src, dst = sys.argv[1], sys.argv[2]
libdir = os.path.join(src, "lib")

def needed(path):
    """DT_NEEDED (sonames) de um ELF64."""
    try:
        d = open(path, "rb").read()
    except OSError:
        return []
    if d[:4] != b"\x7fELF" or d[4] != 2:
        return []
    e_phoff = struct.unpack_from("<Q", d, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", d, 0x36)[0]
    e_phnum = struct.unpack_from("<H", d, 0x38)[0]
    dyn = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if struct.unpack_from("<I", d, off)[0] == 2:  # PT_DYNAMIC
            dyn = struct.unpack_from("<Q", d, off + 8)[0]
            dynsz = struct.unpack_from("<Q", d, off + 32)[0]
    if dyn is None:
        return []
    strtab = None
    entries = []
    for off in range(dyn, dyn + dynsz, 16):
        tag, val = struct.unpack_from("<QQ", d, off)
        if tag == 0:
            break
        if tag == 5:  # DT_STRTAB
            strtab = val
        entries.append((tag, val))
    if strtab is None:
        return []
    out = []
    for tag, val in entries:
        if tag == 1:  # DT_NEEDED
            end = d.index(b"\x00", strtab + val)
            out.append(d[strtab + val:end].decode())
    return out

# fecho: node -> libs -> libs das libs
seen, queue, manifest = set(), needed(os.path.join(dst, "libnode.so")), []
while queue:
    name = queue.pop()
    if name in seen:
        continue
    seen.add(name)
    path = os.path.join(libdir, name)
    if not os.path.isfile(path):
        print(f"   AVISO: {name} não está em {libdir} (fica para o linker do sistema)")
        continue
    # A AGP só empacota ficheiros `lib*.so`: os sonames versionados (libz.so.1,
    # libcrypto.so.3, …) seriam DESCARTADOS. Embarcamos com um alias .so e a app cria
    # uma farm de symlinks (soname -> alias) no seu filesDir; o dlopen segue o link e o
    # alvo está no nativeLibraryDir, que é executável.
    shipped = name if name.endswith(".so") else name.replace(".", "_") + ".so"
    shutil.copy2(path, os.path.join(dst, shipped))
    manifest.append(f"{name}={shipped}")
    with open(os.path.join(dst, "..", "embed-libs.txt"), "w") as fh:
        fh.write("\n".join(manifest) + "\n")
    queue.extend(needed(path))
print("   sonames: " + ", ".join(sorted(seen)))
PY

echo "runtime embutido em $DST:"
ls -lh "$DST" | awk 'NR>1 {printf "   %-28s %s\n", $9, $5}'
# O manifesto (soname -> nome embarcado) tem de ir nos assets para a app construir a farm.
mkdir -p "$WS/app/app/src/main/assets"
cp -f "$WS/.work/jniLibs/embed-libs.txt" "$WS/app/app/src/main/assets/embed-libs.txt"
echo "total: $(du -sh "$DST" | cut -f1)"
echo "manifesto: app/app/src/main/assets/embed-libs.txt"
cat "$WS/app/app/src/main/assets/embed-libs.txt" | sed 's/^/   /'
gitignore="$WS/.gitignore"
grep -q "app/src/main/assets/embed-libs.txt" "$gitignore" 2>/dev/null || printf '\n# runtime embutido (gerado por tools/prepare-embed.sh)\napp/app/src/main/assets/embed-libs.txt\n' >> "$gitignore"
