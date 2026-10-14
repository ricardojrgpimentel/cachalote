# Publicar o repositório — auditoria e checklist

Este documento existe porque o plano é **tornar o repositório público** (e, com isso, resolver o
alojamento do kernel: um *release asset* num repositório público é descarregável pela app sem
token). Antes disso, isto é o que foi verificado e o que ficou por decidir.

## 1. Auditoria de confidencialidade (feita a 2026-10-14)

| Verificação | Comando | Resultado |
|---|---|---|
| Segredos na árvore versionada | `git grep -nIE "sk-[A-Za-z0-9]{16,}\|gho_\|ghp_\|AIza[0-9A-Za-z_-]{20,}\|BEGIN .*PRIVATE KEY"` | **0** |
| Segredos no **histórico** | ficheiros alguma vez adicionados (`.env`, `*credential*`, `*token*`, `*.jks`, `*keystore*`) | **0** |
| Serial do telemóvel | `git grep -nI "R58M55JNP6X"` | **0** (era o valor por omissão em 9 scripts) |
| Caminhos da máquina | `git grep -nI "/Users/ricardopimentel"` | **0** |
| Referências a empresa/interno | `git grep -nIiE "cartrack\|confidencial\|internal use"` | **0** |
| Código LGPL/GPL | menções a LGPL/GPL no repositório | só **notas** a explicar que o *virtual display* do Operit ficou **de fora** |
| Licenças de terceiros | [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) | cobre DSH, Node, Termux, Shizuku, AndroidX, o projecto de referência (MIT) |
| Keystores / segredos de build | `.gitignore` | `.work/` (keystore de release incluído), `.env`, `*.credentials.yaml` |

**Corrigido nesta auditoria:**

1. **Serial do dispositivo** — era `DEV="${CACHALOTE_DEV:-<serial>}"` em nove scripts. Passou a
   [`tools/lib-device.sh`](tools/lib-device.sh): `$CACHALOTE_DEV` → único dispositivo ligado →
   erro explícito se houver vários. Nenhum identificador do aparelho fica no repositório, e os
   scripts passam a funcionar em qualquer máquina.
2. **Caminhos pessoais** em `STATUS.md` e `research/dsh-sdk-surface.md` → `<repo>`.

## 2. Decisões que ficam para ti

### 2.1 Email nos commits

O histórico tem dois autores:

```
36  ricardo <ricardo@local>                 (commits que eu fiz)
12  Ricardo Pimentel <eu@ricardopimen.tel>  (os teus)
```

Um repositório público expõe esse email (é o caso de quase todos os projectos, mas é uma escolha).
Opções:

- **Manter** — zero trabalho; o email fica visível.
- **Reescrever** para o email *noreply* do GitHub (ex.: `ricardojrgpimentel@users.noreply.github.com`):

  ```sh
  # requer git-filter-repo (pip install git-filter-repo)
  git filter-repo --email-callback \
    'return b"ricardojrgpimentel@users.noreply.github.com" if email == b"eu@ricardopimen.tel" else email'
  git push --force origin main
  ```

  **Custo:** reescreve hashes; o clone do outro PC tem de fazer
  `git fetch origin && git reset --hard origin/main` (senão divergem). Não fazer com trabalho por
  comitar nesse PC.

### 2.2 Identificador da app

`dev.ricardopimentel.cachalote` (o teu nome) mantém-se — foi a decisão D9 do plano e é o que a
app instalada usa. Mudar implica reinstalar (perde token/capacidades/auditoria). Nada a fazer por
defeito.

## 3. Checklist para tornar público

```sh
# 1. o repositório
gh repo edit ricardojrgpimentel/cachalote --visibility public --accept-visibility-change-consequences

# 2. publicar o kernel como release asset (num repo público a app descarrega-o sem token)
./tools/prepare-kernel-zip.sh                     # usa/gera .work/kernel.zip
./tools/publish-kernel.sh --repo ricardojrgpimentel/cachalote
#    isto escreve app/app/src/main/assets/kernel.remote.json com o URL + sha256

# 3. reconstruir e instalar a app SEM o kernel embutido (ficheiro de metadados já lá está)
./tools/install-app.sh

# 4. (quando houver release assinada) verificar a instalação de raiz
./tools/build-release.sh
```

Notas:

- `app/app/src/main/assets/kernel.remote.json` está no `.gitignore`: cada pessoa/deploy gera o
  seu (o URL e o hash são específicos do kernel publicado).
- Se mudares o kernel, republica-o e regenera os metadados — o `sha256` antigo faz a app recusar
  o download (de propósito).
- A APK de **debug** instala-se por cima da de debug (mantém os dados); a de **release** tem
  outra chave — usa `./tools/install-app.sh --release` se a app instalada for de release.
- A CI não precisa de segredos: constrói do clone (kernel do npm + runtime do Termux).

## 4. Depois de público

- [ ] Confirmar os três jobs da CI verdes no repositório público.
- [ ] Confirmar que o URL do kernel responde a um `GET` sem autenticação
      (`curl -sI <url> | head -1`).
- [ ] Instalar a app noutro telefone (ou no emulador) e deixar o modo embutido descarregar o
      kernel com verificação de integridade.
- [ ] Verificar que nenhum segredo entrou por engano: `git log -p | grep -iE "sk-[a-z0-9]{20}"`.
