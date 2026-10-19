import {AxiosError, AxiosHeaders} from "axios";
import type {AxiosInstance} from "axios";

declare module "axios" {
    interface AxiosRequestConfig {
        bossIdentityContext?: {
            accountId: string;
            token: string;
            serverUrl: string;
        };
    }
}

export function installBossIdentityTransport(client: AxiosInstance): void {
    client.interceptors.request.use(config => {
        const headers = AxiosHeaders.from(config.headers);
        const canonical = headers.get("X-Boss-Token");
        const legacy = headers.get("Zp_token");
        const canonicalProof = typeof canonical === "string" ? canonical.trim() : "";
        const legacyProof = typeof legacy === "string" ? legacy.trim() : "";
        if (canonicalProof && legacyProof && canonicalProof !== legacyProof) {
            throw new AxiosError("BOSS身份凭据冲突，请刷新后重试", AxiosError.ERR_BAD_REQUEST, config);
        }
        headers.delete("Zp_token");
        const proof = canonicalProof || legacyProof;
        if (proof) {
            headers.set("X-Boss-Token", proof);
        } else {
            headers.delete("X-Boss-Token");
        }
        config.headers = headers;
        return config;
    });
}
