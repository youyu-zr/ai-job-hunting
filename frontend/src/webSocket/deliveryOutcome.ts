export type DeliverySubmissionResult = {
    sent: boolean;
    confirmed?: boolean;
};

/**
 * A BOSS sender can accept a message without echoing the candidate's own
 * message back to the page. The echo is useful evidence, but it is not the
 * delivery prerequisite for continuing the AI reply workflow.
 */
export function isDeliveryAccepted(result: DeliverySubmissionResult | null | undefined): boolean {
    return result?.sent === true;
}
