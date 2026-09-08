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
- Centralizar a decisão de propriedade atual no fluxo já existente de `AppBlockSurfaceResolver` + `UsageImpactRouter`, evitando criar uma segunda regra paralela em `BlockingSessionManager`.
- Fazer a autenticação de `PasswordProtectedTargetUnlockPanel` revalidar esse resolver imediatamente antes de conceder a visita, evitando usar `BlockOverview.dailyLimitEntries` como sinal de bloqueio atual.
- Fazer `UsageImpactRouter` consultar a política canônica do limite para distinguir regra configurada de regra efetivamente bloqueando, inclusive `PAUSE_30`, `BLOCK_UNTIL_TOMORROW` e `TIME` de limite.
- Preservar `getBlockOverview()` como inventário de regras configuradas para não alterar a UI de gerenciamento.
- Preservar o pulso do `BlockingAccessibilityService`, que já detecta a cota atingida durante uma visita e reintercepta o app.
- Preservar o agendamento de meia-noite; TIME/Jejum passa a depender apenas da janela realmente ativa, e não do inventário de regras.

## Revisão feita durante a implementação
A leitura do código mostrou que `AppBlockSurfaceResolver` já era o ponto arquitetural criado para responder "quem é o dono desta interceptação?". Criar uma consulta paralela em `BlockingSessionManager` duplicaria essa responsabilidade e aumentaria o risco de divergência. Por isso, a implementação mantém o plano funcional, mas usa o resolver existente como fonte única da prioridade em runtime.

## Testes de regressão
- PASSWORD isolado continua desbloqueável.
- PASSWORD + limite configurado com cota restante continua desbloqueável.
- PASSWORD + limite atingido não concede visita por senha.
- Limite `BLOCK_UNTIL_TOMORROW` domina após a cota e deixa de dominar no novo dia.
- Limite `PAUSE_30` domina durante a pausa e devolve prioridade depois dela.
- TIME + PASSWORD mantém TIME dominante apenas dentro da janela efetiva.
- Término/saída da janela de TIME devolve prioridade ao PASSWORD.
- Autenticação iniciada antes da cota mas concluída depois é revalidada e falha fechada.

## Critério de conclusão
Nenhuma regra é apagada para resolver prioridade. O mesmo alvo pode manter PASSWORD + limite; apenas o mecanismo atualmente dominante decide a superfície e se um grant pode ser emitido.
