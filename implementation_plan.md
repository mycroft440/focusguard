# Plano de implementação — fluidez ao definir bloqueio do limite de uso

## Objetivo
Eliminar os engasgos percebidos ao configurar um limite de uso de aplicativo, especialmente durante digitação, abertura/fechamento do teclado e navegação para a etapa "Definir bloqueio".

## Diagnóstico
- Os textos dos campos de minutos e duração eram estados do composable que também cria o `ModalBottomSheet`. Cada tecla invalidava o editor inteiro, incluindo header, regras derivadas e estrutura do sheet.
- O sheet possuía altura mínima rígida de `560.dp`, enquanto a `MainActivity` usa `adjustResize`. Com o teclado numérico aberto, essas restrições competiam e provocavam novas medições do painel.
- A troca DETAILS → BLOCK_MODE podia acontecer com um campo ainda focado, combinando resize do IME e troca de uma árvore de conteúdo grande.
- Em paralelo, o serviço de acessibilidade executa o pulso de limites a cada segundo. Antes da correção, ele consultava Room e `UsageStats` de todos os limites antes de descobrir que o foreground era o próprio FocusGuard, gerando I/O desnecessário exatamente enquanto o editor estava sendo usado.

## Alterações
1. Manter o texto digitado e a validação imediata localmente em `AppLimitDetailsScreen`; o estado do `ModalBottomSheet` só recebe um snapshot quando o usuário toca em Continuar.
2. Remover a altura mínima rígida e manter apenas o limite máximo do sheet, permitindo que `adjustResize` responda ao IME sem conflito de constraints.
3. Continuar limpando o foco antes da troca para a etapa de comportamento.
4. Manter um snapshot em memória dos limites ativos no serviço de acessibilidade.
5. Antes de qualquer leitura de Room/`UsageStats` no pulso de 1 segundo, rejeitar FocusGuard, launcher, tela desligada e apps sem limite ativo; quando necessário, medir somente o limite do app em primeiro plano.
6. Preservar callbacks, persistência, monetização, duração da regra e políticas de bloqueio.

## Validação
- Compilar os módulos afetados e executar os testes unitários existentes.
- Confirmar que digitar nos dois campos não invalida o estado pai do bottom sheet.
- Confirmar que abertura/fechamento do teclado não força uma altura mínima incompatível com a janela redimensionada.
- Confirmar que DETAILS → BLOCK_MODE ocorre sem salto brusco com o teclado anteriormente ativo.
- Confirmar por teste unitário que o pulso ignora FocusGuard, launcher e apps sem limite, mas mede um app limitado em foreground.
- Confirmar que a precisão de 1 segundo permanece para o app limitado que está realmente em primeiro plano.
- Confirmar que salvar, editar e remover continuam usando a mesma semântica persistida.

## Critério de conclusão
A tela de limite deve responder continuamente durante digitação e navegação, sem picos periódicos de I/O do enforcement enquanto o próprio FocusGuard está em primeiro plano, mantendo inalterado o comportamento funcional das regras salvas.
