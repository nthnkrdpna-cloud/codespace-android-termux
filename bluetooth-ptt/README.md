# PTT Bluetooth — versão local 0.3.0

Comunicador Push-to-Talk Android, ponto a ponto, com troca de identidade por QR offline e voz por Bluetooth Classic RFCOMM. O tráfego de voz não usa internet, Wi-Fi, Supabase ou servidor remoto.

## O que esta versão implementa

- Serviço Android em primeiro plano para manter o socket e os recursos de áudio ativos enquanto a Activity não está visível.
- Identidade criptográfica persistente por instalação, com chave privada protegida em repouso por uma chave AES do Android Keystore.
- QR de identidade assinado e temporário (validade de cinco minutos), sem endereço MAC, telefone, conta ou endereço de servidor.
- Troca de contatos local: cada aparelho precisa ler e salvar o QR do outro.
- Confirmação visual do fingerprint antes de salvar o contato.
- Handshake mútuo: assinatura ECDSA P-256, ECDH P-256 efêmero e derivação HKDF-SHA-256.
- Proteção dos quadros de áudio com AES-256-GCM, chaves direcionais e contador sequencial contra alteração/repetição de pacotes.
- Captura somente enquanto o botão PTT está pressionado; não há gravação ou envio para nuvem.

## Limites que continuam explícitos

- O QR cadastra e autentica a identidade lógica. Ele não faz o vínculo Bluetooth do sistema operacional.
- O Android ainda exige parear os dois aparelhos nas configurações Bluetooth antes da conexão RFCOMM.
- A conexão é ponto a ponto entre dois telefones; não há salas/grupos, retransmissão ou mesh.
- O áudio usa PCM mono 16 kHz/16-bit. Opus e otimização de consumo/latência ficam para uma etapa posterior.
- Não há reconexão automática; o usuário inicia uma nova sessão manualmente.
- A implementação criptográfica ainda não passou por auditoria independente. Não trate esta versão como mensageiro de alta garantia ou ferramenta para comunicações de risco até revisão especializada e testes.
- Nenhum teste físico entre dois aparelhos está declarado como concluído nesta revisão.

## Requisitos

- Android 8.0 (API 26) ou superior.
- Bluetooth Classic nos dois aparelhos.
- Câmera para ler os QR.
- Permissões de Dispositivos próximos/Bluetooth e Microfone.
- Mesma versão do aplicativo nos dois aparelhos.

## Pareamento QR e conexão local

1. Instale o APK nos dois aparelhos e abra o PTT.
2. No aparelho A, toque **Gerar meu QR de identidade**.
3. No aparelho B, toque **Ler QR do outro aparelho**, leia o QR de A e confira o fingerprint.
4. No aparelho B, gere o próprio QR.
5. No aparelho A, leia o QR de B e confira o fingerprint. A troca precisa ser feita nos dois sentidos.
6. Por um canal confiável, compare os fingerprints exibidos nos dois aparelhos. Só salve quando coincidirem.
7. Abra as configurações Bluetooth do Android e pareie os dois celulares. Isso é uma exigência da camada Bluetooth, independente do QR.
8. No aparelho A, toque **Aguardar conexão Bluetooth**.
9. No aparelho B, escolha **Conectar contato QR pareado**, selecione o contato que foi salvo e depois o dispositivo Bluetooth pareado.
10. Aguarde **CONECTADO — sessão criptografada** nos dois aparelhos.
11. Segure **Segure para falar** para transmitir; solte para encerrar a transmissão. Teste primeiro com volume baixo.
12. Use **Encerrar sessão** ao terminar.

O QR tem validade de cinco minutos. Gere um novo se expirar. Um aparelho que não esteja na lista de contatos QR confiáveis será recusado no handshake, mesmo que esteja pareado pelo Android.

## Compilação

Abra a pasta bluetooth-ptt no Android Studio. Projeto usa Android Gradle Plugin 8.9.1, Gradle 8.11.1 e Java 17.

Comandos:
    cd bluetooth-ptt
    gradle testDebugUnitTest assembleDebug --no-daemon

APK de depuração:
app/build/outputs/apk/debug/app-debug.apk

A automação GitHub Actions também compila o APK debug e publica um artefato temporário na execução do workflow. O APK debug não é uma versão de distribuição assinada.

## Segurança e privacidade

- Nenhuma permissão de internet é declarada no manifesto.
- O QR carrega identidade pública, nome local, nonce, expiração e assinatura; não transporta chave privada.
- O contato salvo fica no armazenamento privado do aplicativo.
- A chave privada de identidade fica cifrada em repouso por AES-GCM com chave não exportável do Android Keystore.
- A sessão de áudio negocia segredo ECDH novo por conexão e usa chaves AES-GCM separadas por direção.
- O contador de quadros precisa estar na sequência esperada; quadros repetidos, adulterados ou inválidos encerram a sessão.
- O fingerprint deve ser comparado fora do próprio QR para reduzir risco de troca de identidade. O nome Bluetooth não é usado como prova de identidade.

## Testes pendentes antes de declarar produção

1. Build e testes automatizados verdes no CI.
2. Instalação em dois Androids físicos, incluindo Moto G75 com Android 16.
3. Verificar troca QR nos dois sentidos, tentativa com QR expirado e tentativa de dispositivo não confiável.
4. Medir latência, cortes, aquecimento e consumo em teste contínuo.
5. Testar rotação, bloqueio de tela, chamadas/interrupções, perda Bluetooth e revogação de permissões.
6. Auditoria independente do protocolo criptográfico e teste de segurança.
7. Build release assinada com chave de distribuição própria.

A versão 0.3.0 é uma versão de teste funcional pretendida, não uma certificação de segurança nem uma alegação de teste físico concluído.