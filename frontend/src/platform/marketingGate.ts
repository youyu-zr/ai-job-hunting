/**
 * Compatibility shim for older imports. Browser code must not decide whether
 * free-text messages are promotion or high risk; the server classifier owns it.
 */
export function shouldSilencePromotionalMessage(
    _message: string,
    _marketingReplyEnabled: boolean,
): boolean {
    return false;
}
