#!/bin/bash
# i18n-check.sh — as duas línguas têm de andar juntas.
#
#   ./tools/i18n-check.sh
#
# Verifica, sem aparelho nenhum (corre na CI):
#   1. os conjuntos de chaves de `values/strings.xml` (inglês, omissão) e
#      `values-pt-rPT/strings.xml` são iguais — uma tradução em falta passa a falhar aqui, em vez
#      de aparecer silenciosamente em inglês no telefone;
#   2. todas as `R.string.<chave>` usadas no Kotlin existem no ficheiro de omissão;
#   3. nenhum texto em pt-PT tem formas brasileiras (tela/arquivo/celular/carregando/você/…);
#   4. **nenhuma legenda explica a disposição** — «toca em «X»», «toca sem largar», «desliza»… Uma
#      legenda dessas existe porque a acção não se vê: o que está mal é o ecrã, não a frase. Este
#      teste é a forma de a regra não se perder quando o próximo a mexer no código não a souber.
#
# Porque existe: o `values-pt-rPT` só se aplica em aparelhos em pt-PT; uma chave em falta **não dá
# erro** — cai no inglês. Só uma comparação dos ficheiros apanha isso.
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
EN="$WS/app/app/src/main/res/values/strings.xml"
PT="$WS/app/app/src/main/res/values-pt-rPT/strings.xml"
[ -f "$EN" ] || { echo "erro: não encontrei $EN"; exit 1; }
[ -f "$PT" ] || { echo "erro: não encontrei $PT"; exit 1; }

falhas=0
chaves() { grep -o 'name="[^"]*"' "$1" | sed 's/name="//; s/"//' | sort; }

echo "== 1. chaves iguais nos dois idiomas =="
chaves "$EN" > /tmp/i18n-en.txt
chaves "$PT" > /tmp/i18n-pt.txt
faltam_pt=$(comm -23 /tmp/i18n-en.txt /tmp/i18n-pt.txt)
faltam_en=$(comm -13 /tmp/i18n-en.txt /tmp/i18n-pt.txt)
if [ -n "$faltam_pt" ]; then
  echo "   [!] sem tradução pt-PT: $(echo "$faltam_pt" | tr '\n' ' ')"
  falhas=$((falhas + 1))
fi
if [ -n "$faltam_en" ]; then
  echo "   [!] só existem em pt-PT: $(echo "$faltam_en" | tr '\n' ' ')"
  falhas=$((falhas + 1))
fi
[ -z "$faltam_pt$faltam_en" ] && echo "   OK  $(wc -l < /tmp/i18n-en.txt | tr -d ' ') chaves nos dois idiomas"

echo "== 2. toda a R.string usada no Kotlin existe =="
usadas=$(grep -rho 'R\.string\.[a-zA-Z0-9_]*' "$WS/app/app/src/main/java" 2>/dev/null | sed 's/R\.string\.//' | sort -u)
em_falta=""
for k in $usadas; do
  grep -q "name=\"$k\"" "$EN" || em_falta="$em_falta $k"
done
if [ -n "$em_falta" ]; then
  echo "   [!] usadas e não definidas:$em_falta"
  falhas=$((falhas + 1))
else
  echo "   OK  $(echo "$usadas" | wc -l | tr -d ' ') chaves usadas, todas definidas"
fi

echo "== 3. pt-PT sem formas brasileiras =="
marcadores="tela celular arquivo carregando você aplicativo registro deletar printar"
achados=""
for m in $marcadores; do
  if grep -qi "$m" "$PT"; then achados="$achados $m"; fi
done
if [ -n "$achados" ]; then
  echo "   [!] pt-BR-ismos no pt-PT:$achados"
  grep -in -E "$(echo "$marcadores" | tr ' ' '|')" "$PT" | head -5
  falhas=$((falhas + 1))
else
  echo "   OK  nenhuma forma brasileira"
fi

echo "== 4. nenhuma legenda explica a disposição =="
# Só padrões que apontam para **dentro** do ecrã. «Liga-o em Definições → Acessibilidade» fica de
# fora de propósito: é noutra app, e sem isso o utilizador não sai do sítio.
explicativas='toca em «|toque em «|toca sem largar|toque sem largar|desliza para|arrasta para|mantém premido'
achados4=""
for f in "$EN" "$PT"; do
  if grep -nE "$explicativas" "$f"; then achados4="$achados4 $(basename "$(dirname "$f")")"; fi
done
if [ -n "$achados4" ]; then
  echo "   [!] legendas a explicar a disposição:$achados4"
  falhas=$((falhas + 1))
else
  echo "   OK  nenhuma (a acção tem de se ver no próprio controlo)"
fi

echo
if [ "$falhas" = 0 ]; then
  echo "i18n: tudo bem"
  exit 0
fi
echo "i18n: $falhas problema(s)"
exit 1
