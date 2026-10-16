export type SilentLoginOutcome = "authenticated" | "legacy-registration-required";

export function classifySilentLoginResponse(response: any): SilentLoginOutcome {
    if (response?.data?.code === 2000) {
        return "legacy-registration-required";
    }
    if (response?.data?.code === 200 && response?.data?.data) {
        return "authenticated";
    }
    throw new Error("静默登录返回无效状态");
}
