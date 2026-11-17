# Raven Correio v0.3.4 — chegada mais lenta, destinatário válido e GUI alinhada

**Minecraft 1.21.1 / NeoForge 21.1.x / Java 21.** Cliente e servidor precisam do mesmo JAR. Configurações e verificações são controladas no servidor. Continuação direta da **v0.3.3**, incluindo as permissões LuckPerms da v0.3.2.

## Correções

### 1. O Carteiro agora chega com calma

- Distância inicial permanece **50 blocos**.
- Velocidade de caminhada padrão passa de `0.48` para **`0.16` bloco/tick** (~3,2 blocos/s a 20 TPS).
- Velocidade de voo padrão passa de `0.85` para **`0.24` bloco/tick** (~4,8 blocos/s a 20 TPS).
- O timeout de aproximação passa de 12 para **19 segundos**. Se o jogador correr continuamente e impedir a chegada, o timeout ainda pode reposicionar o Carteiro próximo dele por segurança.
- Ajustada a cadência visual da caminhada.

**Migração automática:** na primeira execução da v0.3.4, o servidor confere `world/serverconfig/ravencorreio.json`. Se ainda estiver com **ambos** os valores padrão da v0.3.3 (`0.48` e `0.85`) e sem `senderMotionVersion`, ele os substitui por `0.16` e `0.24`, registra `senderMotionVersion: 2` e salva o arquivo. Velocidades já personalizadas são preservadas. Você pode editar os valores após a migração e executar `/correio reload`.

Exemplo de parte do JSON:

```json
{
  "senderMotionVersion": 2,
  "senderSpawnDistance": 50.0,
  "senderWalkSpeed": 0.16,
  "senderFlySpeed": 0.24
}
```

**Não substitua todo o seu JSON** por esse trecho; preserve skin, limite de encomendas e allowedSkinHosts existentes.

### 2. Não aceita mais qualquer nome

- Alvo **online** no servidor: aceito.
- Alvo **offline**: precisa resolver para um perfil compatível **e ter um arquivo de dados do jogador neste mundo** (`world/playerdata/<UUID>.dat`), comprovando uma entrada anterior no servidor.
- Nome inventado, conta existente que **nunca entrou neste servidor** ou perfil que não consegue ser resolvido: **não cria o Carteiro**.
- Envio para si mesmo continua funcionando enquanto `allowSendToSelf` estiver ativado.

**Importante:** essa decisão é intencionalmente mais restritiva do que validar a existência de uma conta Mojang. Ela impede destinatários inexistentes no seu servidor, mesmo quando uma API de perfis retorna um UUID. Um usuário offline que já tenha entrado pode receber normalmente. Em servidores/proxies com UUIDs online/offline inconsistentes, será necessário uniformizar a configuração de autenticação e os dados de perfil.

### 3. Botões simétricos e organizados

Na GUI de envio (9 colunas), a última linha fica assim:

```text
[ ▒ ][ ▒ ][ CANCELAR ][ ▒ ][ ▒ ][ ▒ ][ ENVIAR ][ ▒ ][ ▒ ]
               slot 20                  slot 24
```

- Botão **vermelho** `Cancelar envio`: slot **20**, terceira coluna.
- Botão **verde** `Enviar`: slot **24**, sétima coluna; posição à direita indicada na captura.
- O botão **Retirar tudo** na interface de recebimento continua centralizado no slot **22** (quinta coluna).

## Instalação no Windows

1. Faça backup do seu projeto e do mundo antes de substituir os arquivos.
2. Extraia `RavenCorreio-NeoForge-1.21.1-v0.3.4-patch.zip` na raiz do projeto Java **v0.3.3**, sobrescrevendo os arquivos listados no patch.
3. Mantenha o `gradlew.bat` e a pasta `gradle` da sua instalação Gradle Wrapper anterior.
4. No CMD, dentro da pasta do projeto:

   ```cmd
   gradlew.bat build
   ```

5. Substitua o JAR em `build/libs/` no **cliente e no servidor**; remova JARs de versões anteriores. Reinicie ambos para carregar o código novo. `/correio reload` recarrega o JSON, **não** classes Java.

## Arquivos alterados no patch

- `src/main/java/fun/rederaven/correio/server/CorreioServer.java` — validação de jogadores com histórico no servidor.
- `src/main/java/fun/rederaven/correio/server/ServerSettings.java` — velocidades e migração de configuração.
- `src/main/java/fun/rederaven/correio/entity/CarteiroEntity.java` — duração e animação da aproximação.
- `src/main/java/fun/rederaven/correio/menu/CorreioMenu.java` — posicionamento independente dos botões de envio e recebimento.
- `gradle.properties`, `examples/ravencorreio.json` e `README.md` — versão e documentação.

## Testes rápidos

1. `/correio enviar NickInexistente123`: nenhum NPC deve surgir; o servidor informa falha ou que o jogador nunca entrou.
2. `/correio enviar SeuNick`: apenas um Carteiro se aproxima em cerca de 10 a 16 segundos, dependendo de terreno/voo e dos TPS.
3. `/correio enviar NomeDeJogadorOfflineQueJaEntrou`: deve funcionar caso o perfil seja encontrado e haja `playerdata/<UUID>.dat`.
4. Abrir GUI: botão cancelar vermelho à esquerda e enviar verde à direita, sem sobreposição; retirada mantém botão no centro.
5. Enviar/cancelar itens encantados, nomeados e de mods; inspecionar NBT/componentes antes/depois.
6. Alterar velocidades no JSON e executar `/correio reload` para validar novas configurações, sem reiniciar.

## Estado de validação

Patch feito a partir dos arquivos reais da v0.3.3 disponíveis nesta conversa. Comparações estáticas e integridade dos ZIPs foram verificadas. **A compilação Gradle com NeoForge e o teste dentro do jogo precisam ser realizados no Windows do usuário**; este ambiente não contém o Gradle Wrapper nem dependências NeoForge em cache.
