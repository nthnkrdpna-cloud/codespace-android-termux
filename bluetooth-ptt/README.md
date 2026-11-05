# Prisma Hub + PTT Android — versão 0.4.0

Aplicativo Android único com o PTT Bluetooth local e acesso aos módulos web existentes do Hub.

## O que está no APK

- PTT ponto a ponto por Bluetooth Classic RFCOMM.
- Identidade por instalação, QR assinado temporário, troca bilateral e fingerprint.
- Handshake ECDSA P-256 / ECDH P-256 / HKDF-SHA-256 e quadros AES-256-GCM.
- Hub dentro do mesmo aplicativo, com Portal, Finanças, TV, Terminal/Observatório/Risk Desk, Terceira Margem, Notas, Caderno e Status.
- Termo de uso/privacidade apresentado antes de gerar identidade, solicitar permissões ou iniciar a configuração.
- Identificação local de fabricante, modelo e versão Android para selecionar e exibir um perfil de compatibilidade.

## Configuração após o termo

O app lê apenas fabricante, modelo, versão Android/API e disponibilidade declarada de Bluetooth/câmera. Esses atributos servem para compatibilidade e ficam no aparelho. Não lê IMEI, serial, localização, contatos ou histórico de chamadas.

Depois do aceite, o app:
1. prepara a identidade local com chave privada protegida pelo Android Keystore;
2. solicita ao Android as permissões de Dispositivos próximos/Bluetooth e microfone;
3. pede ao Android para ativar Bluetooth quando necessário;
4. solicita câmera somente quando o usuário abrir a leitura de QR;
5. apresenta um atalho para as configurações de permissões do próprio app.

O aplicativo não altera silenciosamente configurações globais, não usa root, Acessibilidade ou administrador do dispositivo. Permissões e ativação do Bluetooth continuam sob controle do Android e do usuário.

O nome padrão incluído no QR é genérico (`PTT Android`), para não divulgar fabricante/modelo ao contato. Um nome personalizado já existente é preservado.

## Limites funcionais

- A voz PTT permanece local e ponto a ponto. Não usa internet, Wi-Fi, Supabase ou servidor remoto.
- QR estabelece identidade lógica, mas não substitui o pareamento Bluetooth feito nas configurações do Android.
- Os dois aparelhos precisam trocar QR nos dois sentidos, comparar fingerprints por um canal confiável e parear via Bluetooth.
- O Hub WebView usa HTTPS e os serviços web existentes; seus módulos precisam de internet e mantêm as regras de login/permissão do Hub.
- A entrada do portal no Android usa `/index.html`; a raiz pública do domínio não é a rota autenticada do portal.
- Links externos são abertos fora do WebView. Links `intent:` e `stremio:` são permitidos para fluxos de mídia.
- Não há grupos, mesh, Opus ou reconexão automática.
- O protocolo criptográfico não passou por auditoria independente. Não classifique esta versão como mensageiro de alta garantia.
- O APK gerado pelo CI é debug, não release assinada para publicação.

## Compilação e testes automatizados

Requisitos: Android Gradle Plugin 8.9.1, Gradle 8.11.1, Java 17, compile/target SDK 36 e min SDK 26.

```bash
cd bluetooth-ptt
gradle testDebugUnitTest assembleDebug --no-daemon
```

APK de depuração:
`app/build/outputs/apk/debug/app-debug.apk`

O GitHub Actions executa os testes JVM, compila o APK debug e publica o artefato temporário do workflow.

## Testes físicos ainda obrigatórios

A compilação e os testes JVM não comprovam áudio real, compatibilidade de microfone/Bluetooth, persistência de sessão WebView ou reprodução de mídia. Antes de distribuição, instalar em dois Androids físicos e testar:
1. aceite/recusa do termo e persistência da versão;
2. identificação do aparelho e permissões revogadas/concedidas;
3. QR bilateral, QR expirado e fingerprint incorreto;
4. pareamento Android e conexão RFCOMM;
5. transmissão/recepção de áudio, bloqueio de tela, chamadas/interrupções e desconexão;
6. login Portal, permissões de cada módulo, arquivo de extrato, captura de voz em Notas, TV e retorno ao PTT.

## Privacidade

- Voz PTT não sai do Bluetooth.
- A chave privada da identidade local fica cifrada com chave não exportável do Android Keystore.
- O QR contém material público de identidade, nunca a chave privada.
- O aceite do termo é guardado localmente com versão e instante; não é enviado como telemetria.
- O uso online do Hub continua sujeito aos serviços e políticas já usados pelo Hub.
