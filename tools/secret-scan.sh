#!/bin/bash
# secret-scan.sh — não deixar segredos entrar no repositório (nem no histórico).
#
#   ./tools/secret-scan.sh
#
# Porque é que isto existe: o repositório vai ser público e há aqui coisas que **não** podem sair
# — a chave de assinatura da release (`.work/cachalote-release.jks`), o token do canal, a chave do
# provedor. Estão no `.gitignore`, mas um `git add -f`, um copiar-colar ou um descuido põem-nos no
# histórico para sempre (tirar depois obriga a reescrever tudo, como já foi preciso fazer uma vez
# com a identidade dos commits).
#
# Corre em duas frentes:
#   1. **árvore** — ficheiros que o git segue (`.jks`, `.keystore`, `.env`, chaves privadas, tokens);
#   2. **histórico** — nomes de ficheiros que já entraram alguma vez e conteúdo que pareça chave.
#
# Sai com 1 se encontrar algo. Corre na CI a cada push.
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
cd "$WS"

VERMELHO=$'\033[31m'; VERDE=$'\033[32m'; FIM=$'\033[0m'
falhas=0

echo "== 1. árvore (ficheiros que o git segue) =="
# Nomes que nunca podem entrar: chaves de assinatura e ficheiros com segredos.
proibidos=$(git ls-files | grep -iE '\.(jks|keystore|p12|pfx|pem|pkcs12)$|(^|/)(id_rsa|id_ed25519|app\.token|\.env|\.credentials\.yaml)$' || true)
if [ -n "$proibidos" ]; then
  echo "${VERMELHO}   [!] ficheiros proibidos versionados:${FIM}"
  echo "$proibidos" | sed 's/^/       /'
  falhas=$((falhas + 1))
else
  echo "${VERDE}   OK  nenhum ficheiro de chave/segredo versionado${FIM}"
fi

echo "== 2. árvore (conteúdo que pareça segredo) =="
padroes='-----BEGIN [A-Z ]*PRIVATE KEY-----|sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}'
achados=$(git grep -InE "$padroes" -- . ':!*.md' ':!tools/secret-scan.sh' 2>/dev/null | head -20 || true)
if [ -n "$achados" ]; then
  echo "${VERMELHO}   [!] conteúdo suspeito:${FIM}"
  echo "$achados" | sed 's/^/       /' | cut -c1-160
  falhas=$((falhas + 1))
else
  echo "${VERDE}   OK  nenhum segredo com essa cara no código${FIM}"
fi

echo "== 3. histórico (nomes de ficheiros que já entraram) =="
historico=$(git log --all --diff-filter=A --name-only --format= 2>/dev/null \
  | grep -iE '\.(jks|keystore|p12|pfx|pem)$|(^|/)(id_rsa|id_ed25519|app\.token|\.env)$' | sort -u || true)
if [ -n "$historico" ]; then
  echo "${VERMELHO}   [!] estes já estiveram no histórico:${FIM}"
  echo "$historico" | sed 's/^/       /'
  falhas=$((falhas + 1))
else
  echo "${VERDE}   OK  nenhum ficheiro de chave passou pelo histórico${FIM}"
fi

echo "== 4. histórico (conteúdo) =="
# Percorre os commits e procura o mesmo tipo de conteúdo. Com ~80 commits é rápido; num repo
# grande isto passaria a ser caro e valeria a pena trocar por uma ferramenta dedicada.
commits=$(git rev-list --all | wc -l | tr -d ' ')
historico_conteudo=$(git grep -InE '-----BEGIN [A-Z ]*PRIVATE KEY-----|sk-[A-Za-z0-9]{30,}' $(git rev-list --all) 2>/dev/null | head -10 || true)
if [ -n "$historico_conteudo" ]; then
  echo "${VERMELHO}   [!] segredo no histórico:${FIM}"
  echo "$historico_conteudo" | sed 's/^/       /' | cut -c1-160
  falhas=$((falhas + 1))
else
  echo "${VERDE}   OK  nada em $commits commits${FIM}"
fi

echo
if [ "$falhas" = 0 ]; then
  echo "segredos: nada encontrado (árvore e histórico)"
  exit 0
fi
echo "segredos: $falhas problema(s) — NÃO publiques isto antes de resolver"
exit 1
