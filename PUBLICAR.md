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

## 2. Identidade dos commits — ✅ **reescrito**

**Feito em 2026-10-14**, a pedido do autor: o histórico passou a ter **uma só identidade**:

```
Ricardo Pimentel <12200109+ricardojrgpimentel@users.noreply.github.com>
```

(os 75 commits, autor e committer; o email pessoal deixa de aparecer no repositório.)

| | |
|---|---|
| Ferramenta | `git filter-repo --mailmap` (reescreve autor e committer, mantém datas) |
| Conteúdo | **inalterado** — a árvore do novo `HEAD` é idêntica à antiga (`d8f874de76c2`) |
| Antes | `main` = `b9dd9e8ef9f5e600b2e3536e91631816578a0621` · tag `kernel-77fa6785a4e4` = `ca28ace…` |
| Depois | `main` = `8cc2005…` · tag `kernel-77fa6785a4e4` = `a61db73…` |
| Cópia de segurança | `.work/backup-cachalote.git` (clone *mirror* completo do histórico antigo) |
| Verificação | `gh api .../commits?per_page=100` → **75/75** com a identidade nova; release com `kernel.zip` (211 MB) e a APK (42 MB) intacta |

**Noutro computador (importante):** o histórico local fica divergente. Numa cópia de trabalho:

```sh
git fetch origin
git reset --hard origin/main      # e, se houver tags antigas: git fetch --tags --force
```

Não há ramos nem *pull requests* a preservar neste repositório.

**Para commits futuros** basta o que já está configurado no repositório:

```sh
git config user.name  "Ricardo Pimentel"
git config user.email "12200109+ricardojrgpimentel@users.noreply.github.com"
```

E no GitHub, **Settings → Emails → "Keep my email addresses private"** evita que novos *pushes*
voltem a expor o email pessoal.

## 3. O que já está preparado (2026-10-14)

A publicação **já foi feita no repositório privado**, à espera de o tornar público:

| Asset | Onde | Tamanho |
|---|---|---|
| `kernel.zip` | release [`kernel-77fa6785a4e4`](https://github.com/ricardojrgpimentel/cachalote/releases/tag/kernel-77fa6785a4e4) | 211 MB |
| `cachalote-0.3.0-f2.apk` | mesma release (assinada com `.work/cachalote-release.jks`) | 42 MB |

O `app/app/src/main/assets/kernel.remote.json` (não versionado) já aponta para
`https://github.com/ricardojrgpimentel/cachalote/releases/download/kernel-77fa6785a4e4/kernel.zip`
com o `sha256` correspondente — a app descarrega-o e verifica-o.

**Verificado:**

- o asset existe e está completo — pelo API, com o token:
  `HTTP 206` ao pedir 1 KB (a assinatura `PK` confirma o zip);
- o URL de *browser* responde **404 sem autenticação hoje** (repo privado) — é exactamente o
  esperado, e passa a 200 quando o repositório for público. ⚠️ Nota: em repos privados o URL de
  *browser* **não** aceita token; só o endpoint do API (`/releases/assets/<id>` com
  `Accept: application/octet-stream`) serve o ficheiro. Para a app isto é irrelevante: ela usa o
  URL público quando o repo for público.

## 3.1. Checklist para tornar público

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
