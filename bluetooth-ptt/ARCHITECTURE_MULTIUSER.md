# PTT — base multiusuário e integração com Prisma Hub

Status: especificação de arquitetura na branch `feat/ptt-multiusuario-base`. Não representa implementação concluída nem altera o comportamento do APK atual.

## Papel do PTT no projeto

O PTT é o módulo de comunicação de voz local do projeto integrado. Prisma Hub fornece identidade, associação a espaços, preferências e governança quando houver conectividade. O transporte de voz entre aparelhos continua sendo Bluetooth Classic RFCOMM ponto a ponto e não depende de Supabase, Wi-Fi, internet ou login durante a conversa.

A infraestrutura de Amanda é a primeira configuração de referência para onboarding e experiência. Não existe usuário técnico universal chamado Amanda: cada pessoa usa sua própria conta e cada aparelho tem identidade de dispositivo própria.

## Separação de responsabilidades

### Prisma Hub / serviço online
- Autenticar participantes e administrar convites.
- Registrar associação entre conta, workspace e dispositivos.
- Publicar diretório de participantes autorizados e metadados mínimos de pareamento.
- Guardar preferências e eventos administrativos de segurança.
- Permitir revogação de conta, dispositivo ou associação quando houver conectividade.

### Aplicativo PTT / aparelho
- Solicitar permissões Android e gerir o ciclo de vida do microfone.
- Descobrir/selecionar dispositivos já pareados pelo Android.
- Estabelecer socket RFCOMM e transmitir áudio em tempo real.
- Aplicar autenticação criptográfica entre os dois dispositivos antes de liberar áudio.
- Continuar funcionando localmente quando a internet estiver indisponível.
- Exibir claramente estado de conexão, identidade verificada do par e estado do microfone.

### Limite importante
Um convite do Prisma não pareia automaticamente dois rádios Bluetooth. O convite autoriza participação no espaço online; o pareamento Bluetooth e a confiança criptográfica entre dispositivos são etapas distintas e explícitas.

## Modelo de participantes

- **Conta:** pessoa autenticada no Prisma Hub.
- **Workspace:** espaço no qual as pessoas podem se encontrar e receber permissões.
- **Membro:** conta associada a um workspace com papel explícito.
- **Dispositivo:** instalação/aparelho vinculado a uma conta, com chave pública própria e estado revogável.
- **Par confiável:** dispositivo com o qual houve verificação explícita de identidade e estabelecimento de confiança criptográfica.
- **Sessão PTT:** conexão temporária entre dois dispositivos; não equivale a associação permanente ao workspace.

Não usar número de telefone, nome exibido, endereço MAC ou UUID Bluetooth como prova suficiente de identidade. Nomes e endereços de rádio podem mudar ou ser falsificados.

## Fluxo de entrada da Amanda e de futuros participantes

1. Pessoa recebe convite do Prisma Hub limitado a um workspace e a permissões definidas.
2. Autentica com sua própria conta.
3. Registra o aparelho como dispositivo pessoal.
4. Concede permissões Android necessárias.
5. Faz pareamento Bluetooth pelo fluxo do sistema operacional.
6. Confirma a identidade do outro dispositivo por mecanismo criptográfico de verificação explícita (por exemplo, comparação de código curto/QR de impressão digital).
7. Só então o PTT libera o canal de áudio.
8. A pessoa pode revogar seu dispositivo ou sair do workspace sem apagar o histórico privado dos demais.

O PTT local deve permanecer utilizável sem login online após o pareamento e a confiança já terem sido estabelecidos, respeitando a política de revogação offline definida pelo produto. Revogações recebidas só podem ser garantidas quando o dispositivo voltar a sincronizar; a interface deve mostrar a última sincronização e não prometer revogação instantânea offline.

## Estado técnico real do MVP (branch feat/bluetooth-ptt-mvp)

- Transporte: Bluetooth Classic RFCOMM.
- Cenário: dois aparelhos Android, conexão ponto a ponto.
- Áudio: PCM mono, 16 kHz, 16-bit.
- Enquadramento: prefixo de tamanho little-endian isolado em `PcmFrameCodec`, com testes JVM adicionados.
- Pareamento prévio: feito nas configurações Android.
- Dependência de rede: nenhuma para o transporte local.
- Activity atual: ainda é dona do ciclo de vida do socket e do áudio.
- Segundo plano: não implementado.
- Opus: não implementado.
- Autenticação mútua e criptografia de aplicação: não implementadas.
- Reconexão: não implementada.
- Teste físico em dois aparelhos: não confirmado.
- Build/CI e APK verificável: não confirmado.

## Requisitos antes de uso contínuo

1. **Serviço Android real:** mover socket RFCOMM, AudioRecord, AudioTrack e loops de I/O para um serviço foreground devidamente declarado para connectedDevice e microphone, iniciado de forma compatível com as restrições atuais do Android. A Activity deve apenas vincular-se ao serviço e enviar comandos de PTT.
2. **Ciclo de vida:** tratar rotação, retorno à tela, encerramento do serviço, desligamento do Bluetooth, perda do par, permissões revogadas e encerramento do processo.
3. **Áudio:** validar explicitamente códigos de erro de getMinBufferSize, gerir buffers e estados de AudioRecord/AudioTrack, testar eco/feedback e latência. Não afirmar cancelamento de eco sem implementação e teste.
4. **Codec:** avaliar uma implementação Opus Android mantida e verificável. Não adicionar coordenadas Maven ou versões sem confirmar existência, licença, compatibilidade e manutenção.
5. **Segurança:** implementar handshake autenticado e chaves por dispositivo, derivação de chave de sessão, AEAD com nonce único/counter, proteção contra replay e rekey. X25519 + ChaCha20-Poly1305 é uma opção de desenho, não uma implementação já presente. Usar biblioteca criptográfica mantida; não criar criptografia própria.
6. **Pareamento e confiança:** não confiar apenas no bond Bluetooth. Mostrar impressão digital/código de verificação e exigir confirmação explícita na primeira associação.
7. **Reconexão:** estados explícitos, backoff, cancelamento de sockets e recuperação após perda de rádio; nunca reabrir microfone silenciosamente.
8. **Privacidade:** não gravar áudio por padrão; não enviar áudio ao Supabase; não colocar tokens ou chaves privadas em logs, analytics ou arquivos compartilhados.
9. **Distribuição:** build debug automatizado, testes, APK de CI; depois assinatura release própria e verificação de hash.
10. **Teste real:** validar no Moto G75 5G (Android 16) e em pelo menos um segundo aparelho Android, incluindo teste de tela apagada, app em segundo plano, reconexão, permissões e estabilidade.

## Critérios de aceite multiusuário

- Amanda e Caio podem registrar dispositivos separados.
- Um terceiro participante pode entrar por convite limitado no Prisma, sem receber acesso aos dados privados da dupla.
- O diretório do Prisma não permite que um participante se faça passar por outro.
- Uma sessão de voz só começa depois de o par ser autenticado e verificado.
- Dois dispositivos podem conversar localmente sem internet.
- Revogar um dispositivo impede novas sessões após sincronização da revogação; a limitação offline é informada com clareza.
- O serviço mantém a sessão durante a troca de Activity/tela apagada dentro das regras do Android, sem microfone oculto.
- Testes automatizados e teste físico documentado passam antes de chamar o módulo de pronto.

## Não fazer

- Não exigir Supabase ou internet para cada pacote de áudio.
- Não criar conta compartilhada para Amanda e Caio.
- Não usar o nome Bluetooth ou MAC como autenticação.
- Não liberar microfone antes de autenticar o par.
- Não chamar pareamento Android de criptografia ponta a ponta.
- Não declarar Opus, background service, segurança, reconexão ou APK release como concluídos sem código, build e teste correspondentes.

## Próxima implementação

A próxima mudança de código deve ser a extração de uma camada de conexão independente da Activity e, em seguida, a migração de socket + captura + reprodução para um foreground service real. Antes de editar essa camada, revisar o código Java completo e o manifesto atual; preservar o protocolo de framing existente e acrescentar testes de ciclo de vida e cancelamento. Não fazer uma migração parcial que apenas exiba uma notificação enquanto a Activity continua dona do áudio.
