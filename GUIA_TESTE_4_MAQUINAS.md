# Guia de teste em 4 máquinas reais (LAN)

Guia prático para rodar a demonstração em **4 PCs/VMs distintos** na mesma rede
local, formando o anel `A → B → C → D → A`. Para a explicação de como o
protocolo funciona, veja o [`README.md`](README.md); aqui o foco é só o
**procedimento de teste**.

---

## 1. Pré-requisitos

- **JDK 8+** instalado nas 4 máquinas (`java -version` e `javac -version`).
- As 4 máquinas na **mesma sub-rede** (mesmo segmento de broadcast). Em
  Wi-Fi, confirme que o roteador/AP **não** tem "AP/client isolation"
  habilitado (isso bloqueia tráfego entre clientes, inclusive broadcast).
- Em **VMs**, use rede em modo **bridged** (ou uma rede interna/host-only
  compartilhada por todas), nunca NAT isolado por VM — senão o broadcast de
  uma VM não alcança as outras.
- **Firewall liberado para UDP na porta 6000** (entrada) em todas as
  máquinas — veja §3.
- O código-fonte (ou os `.class` já compilados) copiado para as 4 máquinas.

---

## 2. Preparar os arquivos de cada máquina

Cada máquina usa um arquivo de configuração com **apelido único**, em ordem
alfabética. Já existem modelos em `examples/`:

| Máquina | Arquivo |
|---------|---------|
| A | `examples/config_A.txt` |
| B | `examples/config_B.txt` |
| C | `examples/config_C.txt` |
| D | `examples/config_D.txt` |

Os quatro já vêm com os mesmos valores (`tempo=1s`, `erro=20%`,
`timeout=10s`, `tmin=1s`). Mantenha os mesmos parâmetros em todas as
máquinas para a demonstração ficar previsível, e respeite a regra do §4 do
README: **`timeout` > `N × tempo`** (com N=4 e `tempo=1s`, uma volta leva
~4s, então `timeout=10s` está confortável).

Copie o arquivo correspondente para cada PC (ex.: leve só `config_A.txt`
para a máquina que será "A", etc. — não é necessário levar os 4 arquivos
para todo lugar).

---

## 3. Compilar e liberar o firewall em cada máquina

Em cada uma das 4 máquinas, dentro da pasta do projeto:

```bash
make            # gera out/*.class
# ou, sem make:
javac -d out src/*.java
```

Libere UDP/6000 de entrada:

- **Linux (ufw):** `sudo ufw allow 6000/udp`
- **Linux (firewalld):** `sudo firewall-cmd --add-port=6000/udp --permanent && sudo firewall-cmd --reload`
- **Windows (PowerShell como admin):**
  `New-NetFirewallRule -DisplayName "TokenRing UDP 6000" -Direction Inbound -Protocol UDP -LocalPort 6000 -Action Allow`
- **macOS:** Preferências do Sistema → Rede/Firewall → permitir conexões
  entrantes para `java`, ou desative o firewall só durante o teste.

---

## 4. Checagem de rede antes de iniciar

Em cada máquina, anote o IP local (`ip a` / `ifconfig` / `ipconfig`) e
confirme que todas estão na mesma faixa (ex.: `192.168.1.10`,
`192.168.1.11`, `192.168.1.12`, `192.168.1.13`).

- `ping` entre todos os pares de máquinas (4×3 combinações, ou ao menos um
  ciclo) para garantir conectividade básica.
- Se o broadcast `255.255.255.255` não funcionar na sua rede (alguns
  roteadores/VLANs filtram broadcast "global"), use o broadcast **da
  sub-rede** como alvo explícito no 3º argumento, por exemplo
  `192.168.1.255:6000`, em vez do padrão — veja §5.

---

## 5. Iniciar as 4 máquinas

A porta padrão é 6000 e o alvo de descoberta padrão já é o broadcast
`255.255.255.255:6000`, então o comando é o mesmo nas 4 máquinas:

```bash
java -cp out Main examples/config_A.txt   # na máquina A
java -cp out Main examples/config_B.txt   # na máquina B
java -cp out Main examples/config_C.txt   # na máquina C
java -cp out Main examples/config_D.txt   # na máquina D
```

Se precisar forçar o broadcast da sub-rede (em vez do `255.255.255.255`
padrão), informe-o como 3º argumento, por exemplo:

```bash
java -cp out Main examples/config_A.txt 6000 "192.168.1.255:6000"
```

**Ordem de início:** não importa muito — cada máquina espera ~3s após
iniciar antes de cogitar gerar o primeiro token, e só a primeira em ordem
alfabética (`A`) o faz. O ideal é iniciar as 4 dentro de uma janela de
~10-15s. Se alguma entrar depois (ou perder o DISCOVER inicial), use a
opção **6) Reenviar DISCOVER** no menu de qualquer máquina para forçar a
redescoberta.

---

## 6. Validar a formação do anel

No log de cada máquina (e na opção **5) Mostrar estado** do menu), confirme:

- O **anel** mostrado é `A → B → C → D → A` nas 4 máquinas.
- O **mestre** é `A` em todas (quem gerou o primeiro token).
- A linha inicial de log mostra o IP/porta correto de cada máquina — se uma
  máquina tiver várias interfaces de rede (VPN, Docker, Wi-Fi + cabo), o
  programa escolhe automaticamente o primeiro IPv4 de rede local que
  encontrar, o que pode não ser a interface certa. Confira a linha
  `Máquina '<X>' iniciada em <ip>:6000` no log; se o IP estiver errado,
  desligue a interface indesejada (ex.: desconecte a VPN) antes de iniciar.

---

## 7. Roteiro de teste sugerido (cobre os requisitos do enunciado)

Execute em sequência, observando os logs nas 4 telas:

1. **Unicast com sucesso:** em `A`, opção 1, destino `C`, mensagem
   "Ola C". Confirme em `C` o print da mensagem e em `A` o retorno `ACK` e
   remoção da fila.
2. **Erro e retransmissão (NAK):** configure temporariamente
   `probabilidade=100` em `config_B.txt` (ou aguarde a aleatoriedade com
   20%), envie de `B` para `D`. Confirme `NAK` no log de `B` e a
   retransmissão automática na passagem seguinte do token.
3. **Broadcast:** em qualquer máquina, opção 2, mensagem "Oi a todos".
   Confirme que **as outras 3** imprimem a mensagem.
4. **Destino inexistente:** envie (opção 1) para um apelido que não
   existe, ex. `Z`. Confirme retorno `maquinainexistente` e remoção da
   fila.
5. **Retirar/inserir token manualmente:** em uma máquina, opção 4 (retirar
   token); observe no mestre o log de `[MONITOR] TOKEN PERDIDO` após o
   `timeout` configurado, gerando um novo automaticamente. Em outra
   máquina, opção 3 para inserir um token extra e observar a detecção de
   **token duplicado** pelo mestre.
6. **Entrada dinâmica de uma 5ª máquina:** com o anel já estável (só token
   circulando, sem dados em trânsito), inicie um 5º processo com apelido
   `E` (crie um `config_E.txt` análogo) em uma 5ª máquina/terminal e
   confirme que o anel é recalculado para `A → B → C → D → E → A` sem
   reiniciar as outras 4.
7. **Falha de uma máquina (limitação conhecida — confirme antes da
   apresentação):** encerre (Ctrl+C) o processo de uma máquina não-mestre.
   O mestre vai detectar token perdido por timeout e gerar um novo token a
   cada `timeout` segundos (`[MONITOR] TOKEN PERDIDO`) — isso funciona.
   Porém, **`PeerRegistry` nunca remove um apelido depois de descoberto**
   (só há lógica de inserção, em `addOrUpdate`), e `forwardToken()` /
   `forwardData()` sempre usam `successor()` calculado sobre essa lista
   estática. Ou seja, a máquina morta **continua no anel para sempre**: o
   token novo gerado pelo mestre vai morrer de novo ao alcançá-la, e o
   ciclo "timeout → novo token → morre na mesma máquina" se repete
   indefinidamente, sem o anel se reorganizar para excluí-la. O enunciado
   só exige explicitamente a detecção/recuperação de **token perdido ou
   duplicado** (que funciona) e a **inclusão** dinâmica de novas máquinas
   (que também funciona) — não pede remoção de máquinas que caem. Ainda
   assim, vale testar esse cenário **antes** da apresentação e decidir: (a)
   deixar como está e citar a limitação no relatório, ou (b) implementar a
   remoção de peer inativo, se quiser cobrir esse caso também.

---

## 8. Troubleshooting

| Sintoma | Causa provável | Solução |
|---|---|---|
| Nenhuma máquina vê DISCOVER de outra | AP isolation no Wi-Fi, ou VMs em NAT isolado | Desative isolamento de clientes; use bridged/rede interna compartilhada |
| Algumas máquinas se veem, outras não | Sub-redes diferentes (ex.: uma em `192.168.0.x`, outra em `192.168.1.x`) | Coloque todas na mesma sub-rede/VLAN |
| Broadcast não chega a nenhuma máquina | Roteador filtra `255.255.255.255` | Use o broadcast da sub-rede como 3º argumento, ex. `192.168.1.255:6000` |
| `Address already in use` ao iniciar | Outra instância já escutando na 6000 nesse host | Finalize o processo anterior ou use outra porta local (mas todas as demais precisam apontar pra ela) |
| IP mostrado no log está errado | Múltiplas interfaces de rede (VPN, Docker, etc.) | Desative a interface indesejada antes de iniciar (veja §6) |
| Token "perdido" com muita frequência (falso positivo) | `timeout` configurado menor que uma volta completa do anel | Aumente `timeout` (regra: `timeout > N × tempo`) |

---

## 9. Checklist final antes da apresentação

- [ ] JDK instalado e `make` roda sem erro nas 4 máquinas.
- [ ] Firewall liberado para UDP/6000 nas 4 máquinas.
- [ ] As 4 máquinas confirmadas na mesma sub-rede (ping cruzado ok).
- [ ] Anel `A → B → C → D → A` formado e visível na opção 5 de todas.
- [ ] Os 7 cenários do §7 testados e funcionando.
