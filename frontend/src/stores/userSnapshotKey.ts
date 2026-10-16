import {userSessionScope} from "../utils/sessionContext.ts";

export function userSnapshotStorageKey(
    accountId: string | number | null | undefined,
    serverUrl: string | null | undefined
): string | null {
    const scope = userSessionScope(accountId, serverUrl);
    return scope ? `ai-job-user:${scope}` : null;
}
