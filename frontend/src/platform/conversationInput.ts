/**
 * Incoming conversational text is evidence for the AI turn. Do not make a
 * fixed phrase decide whether HR's message reaches the model.
 */
export function shouldDelegateConversationTextToAi(text: string): boolean {
    return Boolean(String(text || '').trim());
}
