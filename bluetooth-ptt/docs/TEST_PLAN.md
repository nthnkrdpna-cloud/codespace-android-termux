# Plano de testes físicos — PTT Bluetooth MVP

| ID | Cenário | Resultado esperado |
|---|---|---|
| BT-01 | Bluetooth desligado ao abrir | App solicita ativação; não trava |
| BT-02 | Permissão de dispositivos próximos negada | Erro claro, sem crash |
| BT-03 | Microfone negado | PTT bloqueado e solicitação explicada |
| BT-04 | Nenhum aparelho pareado | Orientação para parear nas configurações |
| BT-05 | A aguarda; B conecta | Ambos exibem conectado |
| BT-06 | A segura PTT por 5 s | B reproduz áudio contínuo |
| BT-07 | B segura PTT por 5 s | A reproduz áudio contínuo |
| BT-08 | Soltar PTT | Captura encerra rapidamente |
| BT-09 | Desligar Bluetooth durante fala | Socket encerra e UI retorna a desconectado |
| BT-10 | Sair da Activity | Socket fecha; sem microfone oculto |
| BT-11 | Nova tentativa de conexão | Socket anterior não permanece ativo |
| BT-12 | Silêncio e ruído ambiente | Não grava em disco; só transmite enquanto pressionado |

## Métricas
- Tempo de conexão (s)
- Latência boca-ouvido aproximada (ms)
- Cortes audíveis por minuto
- Tempo para parar após soltar (ms)
- Temperatura e consumo após 10 minutos
- Estabilidade com tela ligada

## Bloqueadores de release
- Qualquer áudio capturado antes do PTT.
- Áudio transmitido após soltar o botão.
- Falha ao encerrar microfone/socket.
- Travamento ao perder conexão.
- Ruído ou atraso que inviabilize conversação.
- Ausência de criptografia de aplicação antes de transmitir conteúdo sensível.
