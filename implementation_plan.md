# Plano de implementação — fluidez ao definir bloqueio do limite de uso

## Objetivo
Eliminar os engasgos percebidos ao configurar o comportamento de bloqueio de um limite de uso de aplicativo, com foco na transição para a etapa "Definir bloqueio" e na seleção das opções de bloqueio.

## Diagnóstico
- O editor redesenhado recalculava deadline/calendário em toda recomposição, inclusive quando o usuário apenas alternava o comportamento de bloqueio.
- A troca DETAILS → BLOCK_MODE acontecia enquanto um campo numérico podia continuar com foco/IME aberto, obrigando o `ModalBottomSheet` a processar a troca de conteúdo junto com o resize do teclado.
- As duas etapas possuem alturas diferentes; `weight(fill = false)` permitia mudança brusca da área útil durante a navegação.

## Alterações
1. Memoizar os cálculos derivados do editor pelas dependências reais, especialmente o deadline da regra.
2. Limpar o foco antes de trocar para a etapa de comportamento para dispensar o IME antes da nova tela.
3. Estabilizar a área útil do bottom sheet e fazer as áreas roláveis preencherem o espaço disponível.
4. Preservar integralmente callbacks, persistência, monetização e políticas de bloqueio.

## Validação
- Compilar os módulos afetados e executar os testes unitários existentes de limites.
- Confirmar que editar minutos/duração continua atualizando a validação imediatamente.
- Confirmar que DETAILS → BLOCK_MODE ocorre sem salto brusco com o teclado anteriormente ativo.
- Confirmar que alternar entre "bloquear até amanhã" e "pausa de 30 min" não recalcula o deadline da regra.
- Confirmar que salvar, editar e remover continuam usando a mesma semântica persistida.

## Critério de conclusão
A definição do bloqueio deve responder imediatamente ao toque, sem salto brusco do bottom sheet e sem trabalho de cálculo redundante ao alternar a opção de bloqueio, mantendo inalterado o comportamento funcional das regras salvas.
