PTT Bluetooth — MVP Android

Comunicador Push-to-Talk ponto a ponto para Android, usando exclusivamente Bluetooth Classic RFCOMM (SPP). Sem Wi-Fi, internet, servidor, cadastro ou conta.

## Estado desta entrega

Primeiro MVP de transporte de áudio entre dois aparelhos Android. Usa PCM mono 16 kHz/16-bit em blocos curtos sobre socket RFCOMM. Um aparelho toca AGUARDAR CONEXÃO e o outro CONECTAR A PAREADO.

Limites atuais: aplicativo precisa permanecer aberto e visível; aparelhos devem estar pareados previamente nas configurações Android; ainda não há serviço de áudio em segundo plano, codec Opus, reconexão automática, grupos ou APK release assinado. O áudio ainda não tem criptografia de aplicação ponta a ponta. O pareamento Bluetooth do sistema não substitui criptografia própria.

## Requisitos
- Android 8.0+ (API 26+)
- Bluetooth Classic nos dois aparelhos
- Permissão de dispositivos próximos e microfone
- Aparelhos pareados nas configurações Android

## Construir
Abra esta pasta no Android Studio com Android Gradle Plugin 8.9.1 e Gradle 8.11.1. O wrapper Gradle ainda precisa ser gerado localmente.

```bash
cd bluetooth-ptt
gradle assembleDebug
```

APK debug: app/build/outputs/apk/debug/app-debug.apk.

## Teste físico
1. Instale o mesmo APK nos dois aparelhos.
2. Ative Bluetooth e pareie os aparelhos nas configurações.
3. No aparelho A, toque Aguardar conexão.
4. No aparelho B, toque Conectar a pareado e selecione A.
5. Aguarde Conectado nos dois.
6. Segure Pressione para falar; solte para encerrar.
7. Repita no sentido inverso.

Faça o primeiro teste com volume baixo. Emuladores não validam rádio Bluetooth real.

## Protocolo
- Bluetooth Classic RFCOMM, UUID privado do aplicativo.
- PCM signed 16-bit little-endian, mono, 16.000 amostras/s.
- Prefixo unsigned 16-bit little-endian com tamanho do bloco; blocos de até 640 bytes (~20 ms).
- Socket full duplex; captura local somente enquanto o botão está pressionado.
- Não grava áudio em armazenamento.

## Próximas etapas antes de uso contínuo
1. Medir latência, perdas e estabilidade em Moto G75 + segundo Android.
2. Substituir PCM por Opus para reduzir banda e consumo.
3. Implementar serviço em primeiro plano com tipos connectedDevice|microphone, notificação persistente e início explícito enquanto Activity está visível.
4. Implementar autenticação mútua e criptografia de sessão (por exemplo X25519 + ChaCha20-Poly1305), com chaves verificadas no pareamento inicial.
5. Testes automatizados do protocolo, reconexão, áudio e ciclo de vida.
6. Build release, assinatura própria e verificação do APK.

Não anunciar como equivalente ao Zello até concluir testes de rádio, áudio em segundo plano, segurança e reconexão.
