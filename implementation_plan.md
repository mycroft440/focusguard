# Plano de implementação — fluidez ao definir bloqueio do limite de uso

## Objetivo
Eliminar os engasgos percebidos ao configurar o comportamento de bloqueio de um limite de uso, com foco na transição para a etapa "Definir bloqueio" e na seleção das opções de bloqueio.

## Diagnóstico
- O editor redesenhado recalcula deadline/calendário em toda recomposição, inclusive quando o usuário apenas alterna o comportamento de bloqueio.
- A troca DETAILS → BLOCK_MODE acontece enquanto um campo numérico pode continuar com foco/IME aberto, obrigando o `ModalBottomSheet` a relayouts concorrentes com a troca de conteúdo.
- As duas etapas podem ter alturas muito diferentes; o uso de `weight(fill = false)` permite que a folha mude de altura abruptamente durante a navegação.
- O editor de segurança compartilhado dispara um `LaunchedEffect` para cada alteração de texto/dias/modo apenas para propagar um booleano de confirmação, criando trabalho assíncrono e recomposições desnecessárias.
- `SecurityChoice` possui click handler tanto na linha quanto no `RadioButton`, embora a linha inteira já seja o alvo de interação.

## Alterações
1. Tornar os cálculos derivados do editor de app memoizados por suas dependências reais, especialmente o deadline da regra.
2. Limpar foco e solicitar ocultação do teclado antes de trocar para a etapa de comportamento.
3. Estabilizar a área útil das etapas do bottom sheet para evitar salto de altura durante a troca de tela.
4. Fazer a confirmação do `LimitSecuritySection` reagir somente quando o resultado booleano realmente muda.
5. Remover o segundo click handler do `RadioButton`, deixando a linha como alvo único e consistente.
6. Preservar integralmente a semântica de persistência, monetização e políticas de bloqueio.

## Validação
- Compilar os módulos afetados e executar testes unitários existentes de limites.
- Confirmar que editar minutos/duração continua atualizando a validação imediatamente.
- Confirmar que DETAILS → BLOCK_MODE ocorre com o teclado sendo dispensado antes da troca.
- Confirmar que alternar entre as opções de bloqueio não recalcula o deadline da regra.
- Confirmar que site/palavra continua validando NONE/PASSWORD/TIME sem callbacks repetidos por cada caractere.

## Critério de conclusão
A definição do bloqueio deve responder imediatamente ao toque, sem salto brusco do bottom sheet, sem trabalho assíncrono redundante e sem alterar o comportamento funcional das regras salvas.
