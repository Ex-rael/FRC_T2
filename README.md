# Simulador de Rede Local em Anel (Token Ring sobre UDP)

Implementação em **Java** do trabalho final de Redes de Computadores descrito em
[`Definition.md`](Definition.md). A aplicação simula uma rede local em **anel**,
na qual as máquinas trocam mensagens usando **UDP** como transporte e um **token**
controla quem pode transmitir a cada momento.

Este documento explica, **passo a passo**, como o sistema funciona e como
executá-lo. Ele também serve como relatório da solução (estruturas de dados,
threads, sincronização, CRC e exemplos de execução).

---

## 1. Visão geral

Cada máquina é um processo Java independente que:

1. **Descobre** as outras máquinas por broadcast (DISCOVER/HELLO) e monta o anel
   em **ordem alfabética** dos apelidos (`A → B → C → A`).
2. Recebe e repassa um **token** ao seu sucessor. Só quem está com o token pode
   transmitir dados.
3. Mantém uma **fila de até 10 mensagens**. Ao receber o token, envia a primeira
   mensagem da fila; os dados percorrem todo o anel e **voltam à origem** antes de
   o token seguir adiante.
4. Insere um **CRC32** em cada mensagem e pode **injetar erros** (com uma
   probabilidade configurável) para exercitar a detecção de falhas (ACK/NAK).
5. A **máquina inicial** (a primeira a entrar na rede) gera o primeiro token e o
   **monitora**: detecta token perdido (timeout) e token duplicado (intervalo
   mínimo).

```
              token / dados
        ┌───────────────────────┐
        ▼                       │
      [ A ] ───► [ B ] ───► [ C ]
        ▲                       │
        └───────────────────────┘
```

---

## 2. Estrutura do código

Todas as classes ficam em `src/` (pacote padrão):

| Arquivo                | Responsabilidade |
|------------------------|------------------|
| `Config.java`          | Lê o arquivo de configuração (apelido, tempos, probabilidade de erro). |
| `Peer.java`            | Endpoint de uma máquina conhecida (apelido + IP + porta). |
| `PeerRegistry.java`    | Tabela de máquinas e cálculo da topologia do anel (sucessor; mestre = quem entrou primeiro). |
| `OutgoingMessage.java` | Mensagem na fila de saída (destino + conteúdo + estado de retransmissão). |
| `MessageQueue.java`    | Fila limitada (10) e *thread-safe* de mensagens de saída. |
| `Packet.java`          | Constantes do protocolo, montagem das strings dos pacotes e **CRC32**. |
| `DataPacket.java`      | Interpretação (`parse`) do pacote de dados `2000:...`. |
| `RingNode.java`        | **Núcleo**: socket UDP, threads, token, dados, falhas e monitor. |
| `Main.java`            | Ponto de entrada e **menu interativo**. |

Arquivos de apoio: `Makefile`, configurações de exemplo em `examples/` e este
`README.md`. A pasta `references/` contém os exemplos de UDP em Java/C usados
como base inicial.

---

## 3. Como compilar e executar

Requisitos: **JDK 8 ou superior** (`javac`/`java`).

### Compilar

```bash
make          # gera os .class em out/
# ou, sem make:
javac -d out src/*.java
```

### Executar

```
java -cp out Main <arquivo_config> [porta_local=6000] [alvos_descoberta]
```

- `arquivo_config` – arquivo com as 5 linhas de configuração (ver §4).
- `porta_local` – porta UDP em que a máquina escuta (padrão **6000**).
- `alvos_descoberta` – para onde enviar DISCOVER/HELLO. Padrão:
  `255.255.255.255:6000` (broadcast). Em testes na **mesma máquina**, informe os
  pares `host:porta`, aceitando intervalos, ex.: `127.0.0.1:6000-6002`.

#### a) Demonstração em 3 PCs na mesma LAN (cenário do enunciado)

Cada PC usa a porta padrão 6000 e o broadcast da rede. Basta:

```bash
java -cp out Main config.txt
```

As máquinas se encontram por broadcast e se identificam pelo **IP** de origem dos
pacotes — exatamente o que permite a interoperação com implementações de outros
grupos.

#### b) Demonstração local com 3 máquinas (1 só computador)

Abra **3 terminais**. Como não é possível ter três processos na mesma porta de
broadcast, cada um usa uma porta distinta e a descoberta é enviada para o
intervalo de portas:

```bash
make run-a   # A  -> porta 6000
make run-b   # B  -> porta 6001
make run-c   # C  -> porta 6002
```

Equivalente sem `make`:

```bash
java -cp out Main examples/config_A.txt 6000 "127.0.0.1:6000-6002"
java -cp out Main examples/config_B.txt 6001 "127.0.0.1:6000-6002"
java -cp out Main examples/config_C.txt 6002 "127.0.0.1:6000-6002"
```

---

## 4. Arquivo de configuração

Cinco linhas, uma informação por linha (linhas em branco e iniciadas por `#` são
ignoradas). Aceita vírgula ou ponto no decimal.

```
<apelido_da_máquina>
<tempo_do_token_e_dos_dados>      (segundos)
<probabilidade_de_erro>           (0..100 %)
<timeout_do_token>                (segundos)
<tempo_mínimo_entre_tokens>       (segundos)
```

Exemplo (`examples/config_A.txt`, comentado):

```
A
1      # tempo do token e dos dados (atraso por salto, para visualização)
20     # 20% de chance de inserir erro em cada mensagem enviada
10     # se o token sumir por 10 s, a máquina mestre gera um novo
1      # se o token voltar em < 1 s, há token duplicado -> remove um
```

> **Dica de ajuste (importante):** o token é segurado `tempo` segundos em cada
> máquina (para que dê para *ver* a circulação). Logo, uma volta completa leva
> cerca de `N × tempo` segundos para `N` máquinas. Configure o **timeout** maior
> que uma volta (`timeout > N × tempo`) e o **tempo mínimo** menor que uma volta,
> para evitar falsos positivos.

---

## 5. Formato dos pacotes

Todos os pacotes são **strings**. O primeiro número identifica o tipo.

| Tipo     | Valor  | Formato                                                   | Exemplo |
|----------|--------|-----------------------------------------------------------|---------|
| DISCOVER | `10`   | `10:<apelido>:<ip>:<entrada>`                            | `10:A:10.32.143.20:1718000000000` |
| HELLO    | `20`   | `20:<apelido>:<ip>:<entrada>`                            | `20:B:10.32.143.21:1718000000000` |
| TOKEN    | `1000` | `1000`                                                   | `1000` |
| DADOS    | `2000` | `2000:<origem>:<destino>:<controle>:<crc>:<mensagem>`     | `2000:B:A:maquinainexistente:19385749:Oi pessoal!` |

O campo **controle** assume `maquinainexistente`, `ACK` ou `NAK`. A mensagem é
sempre o **último** campo, então pode conter `:` (a divisão usa limite 6).

O campo **entrada** (epoch ms em que a máquina entrou na rede) é uma **extensão**
deste trabalho, usada para eleger a máquina mestre pelo tempo de entrada (ver §6,
Passo 3). Ele vai no **fim** do DISCOVER/HELLO para não atrapalhar implementações
que leem apenas `tipo:apelido:ip`; pacotes sem esse campo continuam sendo aceitos.

---

## 6. Passo a passo do funcionamento

### Passo 1 — Inicialização e descoberta (DISCOVER / HELLO)

Ao iniciar, a máquina cria um socket UDP, e **envia DISCOVER em broadcast** se
identificando. Quem recebe um DISCOVER:

- registra o remetente (apelido + IP:porta de origem do pacote);
- responde com **HELLO em broadcast**, também se identificando.

Assim, todas as máquinas ficam conhecendo todas. A identidade lógica é o
**apelido**; o endereço de rede é aprendido a partir da **origem do pacote**, o
que faz a solução funcionar tanto em vários PCs (porta 6000, identificação por
IP) quanto localmente (portas distintas).

### Passo 2 — Formação do anel

A lista de máquinas conhecidas é ordenada **alfabeticamente** e tratada de forma
**circular**: o sucessor de cada máquina é o próximo apelido da lista, e o último
liga-se ao primeiro (`A → B → C → A`). O anel é recalculado sempre que uma nova
máquina é descoberta.

### Passo 3 — Geração do token

A **máquina inicial** (a **primeira a entrar na rede**) gera o primeiro token
assim que a descoberta estabiliza e o envia ao sucessor. Qualquer máquina também
pode **inserir** um token manualmente pelo menu (opção 3).

> **Como o mestre é escolhido:** cada máquina carimba seu instante de entrada
> (epoch ms) ao iniciar e o anuncia no DISCOVER/HELLO. O mestre é a máquina de
> **menor carimbo** (empate é desempatado pelo menor apelido). Como o carimbo é
> gerado uma única vez e propagado igual para todas, todas elegem o mesmo mestre,
> mesmo com relógios não sincronizados. **Atenção:** isto difere do enunciado, que
> define o mestre como a primeira máquina em **ordem alfabética**. A ordem do anel
> (sucessor) continua sendo alfabética.

### Passo 4 — Recebimento do token

Quando uma máquina recebe o token, ela o segura por `tempo` segundos (para
visualização) e então:

- **fila vazia** → repassa o token ao sucessor;
- **fila com mensagens** → retira a primeira mensagem e a envia (Passo 5),
  **retendo o token** até os dados voltarem.

### Passo 5 — Envio de dados e inserção de falhas (CRC32)

A origem monta o pacote `2000:origem:destino:maquinainexistente:CRC:mensagem`:

1. calcula o **CRC32** sobre a mensagem **correta**;
2. o **módulo de inserção de falhas** sorteia, com a probabilidade configurada,
   se vai **corromper o conteúdo** (mantendo o CRC original — assim o destino
   detectará a divergência);
3. envia ao sucessor com o controle inicial `maquinainexistente`.

### Passo 6 — No destino (ACK / NAK)

Cada máquina que recebe um pacote de dados verifica o **destino**:

- **não é para ela** → repassa ao sucessor sem alterar;
- **é para ela** → recalcula o CRC, **imprime origem + mensagem** e troca o
  controle para **`ACK`** (CRC confere) ou **`NAK`** (CRC diverge), repassando o
  pacote adiante para que ele continue a volta até a origem.

### Passo 7 — De volta na origem

Quando o pacote retorna a quem o originou (origem = seu apelido), a mensagem deu
a volta completa. Conforme o controle:

- **`ACK`** → entregue com sucesso → remove a mensagem da fila e repassa o token;
- **`maquinainexistente`** → o destino não está na rede → avisa na tela, remove a
  mensagem e repassa o token;
- **`NAK`** → o destino detectou erro → **retransmite uma única vez**, trocando
  `NAK` por `maquinainexistente` e enviando a **mensagem original sem erro** na
  próxima passagem do token. A mensagem **não** é removida da fila. Se voltar
  `NAK` de novo, é descartada (limite de uma retransmissão).

### Passo 8 — Broadcast

Mensagens para o apelido reservado **`BROADCAST`** são lidas e impressas por
**todas** as máquinas. O módulo de falhas mantém o controle em
`maquinainexistente` (não há ACK/NAK). Ao completar a volta, a origem remove a
mensagem e repassa o token.

### Passo 9 — Controle do token (detecção e recuperação de falhas)

A máquina **mestre** monitora o token:

- **Token perdido (timeout):** se nenhum token/atividade circula há mais que
  `timeout` segundos, o mestre **gera um novo token**. (Há também a opção 4 para
  **retirar** um token da rede.)
- **Token duplicado:** se o token retorna ao mestre em menos que o `tempo mínimo`
  entre tokens, conclui-se que **há mais de um token** e o mestre **remove** o
  excedente. (Qualquer máquina pode **gerar** um token pela opção 3.)

### Passo 10 — Alteração topológica (entrada de novas máquinas)

Uma nova máquina pode entrar **a qualquer momento**: ao iniciar, envia DISCOVER,
as demais respondem HELLO e o anel é recalculado dinamicamente, sem reiniciar o
sistema. Recomenda-se incluí-la quando apenas o token circula (sem dados em
trânsito), como pede o enunciado.

---

## 7. Menu de operação

Cada máquina exibe um menu (e registra no console tudo o que acontece — onde está
o token e os dados, retransmissões, token perdido/duplicado, etc.):

```
 1) Enviar mensagem (unicast)        -> pede destino e texto; enfileira
 2) Enviar mensagem em BROADCAST     -> enfileira para todos
 3) Inserir/gerar token na rede
 4) Retirar token da rede
 5) Mostrar estado (anel, fila, token)
 6) Reenviar DISCOVER (redescobrir o anel)
 0) Sair
```

---

## 8. Threads e sincronização

| Thread       | Função |
|--------------|--------|
| `receiver`   | Bloqueia em `socket.receive()` e trata **um pacote por vez** (serializa as transições de estado do token). |
| `monitor`    | Detecta token perdido/duplicado (na mestre) e recupera dados presos. |
| `bootstrap`  | Faz a máquina inicial gerar o primeiro token após a descoberta. |
| `menu`       | Lê o teclado (em `Main`) e interage com o nó. |

**Sincronização:** o processamento de pacotes é feito apenas pela thread
`receiver`, evitando disputas sobre o estado do token. As flags compartilhadas
com as outras threads são `volatile`; a `MessageQueue` e o `PeerRegistry` têm
métodos `synchronized`; e os envios pelo socket são protegidos por um *lock*
dedicado (`sendLock`) para não intercalar bytes de pacotes diferentes.

## 9. CRC

Usa-se `java.util.zip.CRC32` sobre os **bytes UTF-8** da mensagem. A origem grava
o CRC da mensagem correta no pacote; o destino recalcula o CRC do conteúdo
recebido e compara — se diferir, marca **NAK**; se igual, **ACK**.

---

## 10. Exemplos de execução (saída real)

### Descoberta, anel e envio com sucesso (`A` envia "Ola C" para `C`)

```
[A] DISCOVER de 'B' (127.0.0.1:6001). Nova topologia: A → B → A
[A] HELLO de 'C' (127.0.0.1:6002). Nova topologia: A → B → C → A
[A] [MONITOR] sou a máquina inicial do anel ('A'). Gerando o primeiro token.
[A] [TOKEN] repassado para 'B'.
[A] [TOKEN] recebido. Há mensagens na fila.
[A] [DADOS] origem 'A' -> destino 'C'. Enviando para 'B': 'Ola C' (CRC=1580590497, controle=maquinainexistente).
[B] [DADOS] de 'A' para 'C' (não é para mim). Repassando.
[C] [DADOS] SOU O DESTINO. Mensagem de 'A': 'Ola C'. CRC recebido=1580590497 calculado=1580590497 -> ACK.
[A] [ORIGEM] retorno ACK de 'C': entregue com sucesso. Removendo da fila.
[A] [TOKEN] repassado para 'B'.
```

### Inserção de erro, NAK e retransmissão (probabilidade 100%)

```
[A] [FALHA] erro inserido propositalmente na mensagem para 'C'.
[A] [DADOS] origem 'A' -> destino 'C'. Enviando para 'B': 'Mensage# com erro' (CRC=959474145, ...).
[C] [DADOS] SOU O DESTINO. Mensagem de 'A': 'Mensage# com erro'. CRC recebido=959474145 calculado=3108200178 -> NAK.
[A] [ORIGEM] retorno NAK de 'C': erro detectado. A mensagem será RETRANSMITIDA (sem erro) na próxima passagem do token.
[A] [RETRANSMISSÃO] reenviando mensagem para 'C' sem erro.
[A] [DADOS] origem 'A' -> destino 'C'. Enviando para 'B': 'Mensagem com erro' (CRC=959474145, ...).
[C] [DADOS] SOU O DESTINO. Mensagem de 'A': 'Mensagem com erro'. CRC recebido=959474145 calculado=959474145 -> ACK.
```

### Destino inexistente (`A` envia para `Z`, que não existe)

```
[A] [DADOS] origem 'A' -> destino 'Z'. Enviando para 'B': 'Alguem ai' (..., controle=maquinainexistente).
[A] [ORIGEM] retorno 'maquinainexistente': destino 'Z' não está na rede. Removendo da fila.
```

### Token retirado e recuperado pelo monitor

```
[A] [TOKEN] retirado da rede a pedido do usuário.
[A] [MONITOR] TOKEN PERDIDO: sem atividade no anel há 10015ms (> timeout 10000ms). Gerando um novo token.
[A] [TOKEN] repassado para 'B'.
```

---

## 11. Observações e limitações

- **Interoperação:** os pacotes de **token** e **dados** seguem fielmente o
  enunciado. Há duas diferenças no DISCOVER/HELLO e na escolha do mestre: o
  DISCOVER/HELLO leva um campo extra de **entrada** no fim (ver §5) e o **mestre**
  é eleito por **tempo de entrada** (não por ordem alfabética). O campo extra é
  ignorado por quem lê só `tipo:apelido:ip`, mas a eleição do mestre **não** será
  compatível com grupos que usem a regra alfabética. Para a apresentação entre
  grupos, use a porta padrão **6000** e identifique as máquinas pelo **IP** (modo
  LAN, item 3a).
- **Envio para si mesmo:** mensagens cujo destino é o próprio apelido retornam
  como `maquinainexistente` (cenário degenerado; não é um caso de uso real).
- **Ajuste de tempos:** veja a dica no §4. Tempos mal dimensionados podem gerar
  falsos "token perdido" ou "token duplicado".
- **Entrada de novas máquinas:** faça-a com apenas o token circulando, conforme o
  enunciado, para não interromper um pacote de dados em trânsito.
