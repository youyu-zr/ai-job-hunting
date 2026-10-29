export type SilentLoginOutcome = "authenticated" | "legacy-registration-required" | "identity-refused";

/** The backend's "this identity was not accepted" business code. */
const IDENTITY_VERIFY_FAILED = 2003;

export function classifySilentLoginResponse(response: any): SilentLoginOutcome {
    if (response?.data?.code === 2000) {
        return "legacy-registration-required";
    }
    if (response?.data?.code === IDENTITY_VERIFY_FAILED) {
        // A refusal the backend already explained. Surface its message instead of replacing it
        // with a generic one: "the account is not registered" and "the credential was rejected"
        // are different problems and the user can act on the first.
        return "identity-refused";
    }
    if (response?.data?.code === 200 && response?.data?.data) {
        return "authenticated";
    }
    throw new Error("静默登录返回无效状态");
}

/** The message the backend attached to a refusal, if it sent one. */
export function silentLoginRefusalMessage(response: any): string {
    const message = response?.data?.message;
    return typeof message === "string" && message.trim()
        ? message.trim()
        : "身份校验失败，请确认已登录本人 BOSS 账号后重试";
}
