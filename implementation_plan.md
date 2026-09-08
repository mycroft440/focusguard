# Plano de implementação — hierarquia de bloqueios

## Objetivo
Corrigir a coexistência entre bloqueio por senha, limite diário e bloqueio por tempo sem destruir nenhuma proteção configurada. A proteção dominante deve ser resolvida dinamicamente conforme o estado atual.

## Hierarquia funcional
1. Proteções irreversíveis/fortes ativas (TIME/Jejum, Pomodoro estrito, Focus Mode) dominam qualquer sessão PASSWORD.
2. Um limite diário domina PASSWORD apenas quando está efetivamente bloqueando naquele instante.
3. Um limite diário apenas configurado, com cota restante ou pausa já concluída, não pode impedir o desbloqueio por senha.
4. Ao atingir a cota durante uma visita autenticada, o limite deve assumir imediatamente.
5. Quando o limite deixa de bloquear (fim da pausa ou reset local da meia-noite), PASSWORD volta automaticamente a assumir se continuar configurado.

## Alterações previstas
- Centralizar em `BlockingSessionManager` uma consulta read-only para saber se um alvo está sendo bloqueado agora por limite diário.
- Fazer `AppBlockSurfaceResolver` e a autenticação de `PasswordProtectedTargetUnlockPanel` dependerem dessa consulta, evitando usar `BlockOverview.dailyLimitEntries` como sinal de bloqueio atual.
- Preservar `getBlockOverview()` como inventário de regras configuradas para não alterar a UI de gerenciamento.
- Preservar o pulso do `BlockingAccessibilityService`, que já detecta a cota atingida durante uma visita e reintercepta o app.
- Preservar o agendamento de meia-noite e a política de TIME, salvo ajuste pontual caso os testes revelem inconsistência.

## Testes de regressão
- PASSWORD isolado continua desbloqueável.
- PASSWORD + limite configurado com cota restante continua desbloqueável.
- PASSWORD + limite atingido não concede visita por senha.
- Limite `BLOCK_UNTIL_TOMORROW` domina após a cota e deixa de dominar no novo dia.
- Limite `PAUSE_30` domina durante a pausa e devolve prioridade depois dela.
- TIME + PASSWORD mantém TIME dominante.
- Término do TIME devolve prioridade ao PASSWORD.
- Autenticação iniciada antes da cota mas concluída depois é revalidada e falha fechada.

## Critério de conclusão
Nenhuma regra é apagada para resolver prioridade. O mesmo alvo pode manter PASSWORD + limite; apenas o mecanismo atualmente dominante decide a superfície e se um grant pode ser emitido.
