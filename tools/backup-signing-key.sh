#!/bin/bash
# backup-signing-key.sh — guardar (e repor) a chave de assinatura da release.
#
#   ./tools/backup-signing-key.sh --verificar
#   CACHALOTE_BACKUP_PASS='a-tua-frase' ./tools/backup-signing-key.sh --criar
#   CACHALOTE_BACKUP_PASS='a-tua-frase' ./tools/backup-signing-key.sh --restaurar cachalote-chave.jks.enc
#
# Porque é que isto importa: uma app instalada só aceita actualizações assinadas pela **mesma**
# chave. Se o `.work/cachalote-release.jks` se perder, quem tem a app instalada fica sem
# actualizações (tem de desinstalar e reinstalar). E o ficheiro **não** está no repositório (é um
# segredo): se este portátil se perder sem cópia, perde-se.
#
# O que guardar, e onde:
#   · o ficheiro `.work/cachalote-release.jks`;
#   · a password da keystore (por omissão `cachalote`, definida no build-release.sh — muda-a);
#   · o **SHA-256 do certificado** (em baixo, em --verificar): é o que prova que a cópia restaurada
#     é a mesma identidade de assinatura.
#
# Guarda o `.enc` num gestor de palavras-passe (com anexos) ou num disco externo cifrado — não num
# sítio público nem no próprio repositório.
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
KS="$WS/.work/cachalote-release.jks"
KT="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
STORE_PASS="${CACHALOTE_STORE_PASS:-cachalote}"
ALIAS="${CACHALOTE_KEY_ALIAS:-cachalote}"

verificar() {
  [ -f "$KS" ] || { echo "erro: não encontrei $KS"; return 1; }
  echo "ficheiro : $KS ($(wc -c < "$KS" | tr -d ' ') bytes)"
  echo "sha256   : $(shasum -a 256 "$KS" 2>/dev/null | awk '{print $1}')"
  if [ -x "$KT" ]; then
    echo "certificado:"
    "$KT" -list -v -keystore "$KS" -storepass "$STORE_PASS" -alias "$ALIAS" 2>/dev/null \
      | grep -iE "alias name|valid from|SHA256:" | sed 's/^/  /'
  else
    echo "(keytool não encontrado em $KT — sem detalhes do certificado)"
  fi
  cat <<'FIM'

Para confirmar uma cópia restaurada, compara o **SHA-256 do certificado** acima com o que tinhas
guardado (e não o do ficheiro: a keystore pode ser regravada com os mesmos dados e mudar de bytes).
FIM
}

criar() {
  [ -f "$KS" ] || { echo "erro: não encontrei $KS"; return 1; }
  : "${CACHALOTE_BACKUP_PASS:?define CACHALOTE_BACKUP_PASS com a frase que queres usar para cifrar}"
  local saida="${1:-$WS/cachalote-chave.jks.enc}"
  tar czf - -C "$WS" .work/cachalote-release.jks \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:CACHALOTE_BACKUP_PASS -out "$saida" \
    || { echo "erro: falhou a cifra"; return 1; }
  echo "cópia cifrada: $saida ($(wc -c < "$saida" | tr -d ' ') bytes)"
  echo "sha256 da cópia: $(shasum -a 256 "$saida" | awk '{print $1}')"
  cat <<'FIM'

A seguir:
  1. põe esse ficheiro num gestor de palavras-passe (com anexos) ou num disco cifrado;
  2. guarda lá também a password da keystore e o SHA-256 do certificado (--verificar);
  3. apaga o ficheiro do projecto quando já tiveres a cópia a salvo.
FIM
}

restaurar() {
  local origem="${1:?uso: --restaurar <ficheiro.enc>}"
  : "${CACHALOTE_BACKUP_PASS:?define CACHALOTE_BACKUP_PASS com a frase usada para cifrar}"
  [ -f "$origem" ] || { echo "erro: não encontrei $origem"; return 1; }
  mkdir -p "$WS/.work"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:CACHALOTE_BACKUP_PASS -in "$origem" \
    | tar xzf - -C "$WS" || { echo "erro: falhou a decifra (frase errada?)"; return 1; }
  echo "restaurado em $KS"
  verificar
  cat <<'FIM'

A seguir, confirma que é a MESMA identidade:
  ./tools/build-release.sh            # constrói e mostra o SHA-256 do certificado
e compara com o que guardaste.
FIM
}

case "${1:-}" in
  --verificar) verificar ;;
  --criar) shift; criar "${1:-}" ;;
  --restaurar) shift; restaurar "${1:-}" ;;
  *) echo "uso: $0 --verificar | --criar [saida.enc] | --restaurar <ficheiro.enc>"; exit 2 ;;
esac
