package com.maple.ai.job.hunting.model.vo;

import lombok.Data;

/** Runtime state of the global AI seat and one job conversation. */
@Data
public class AiSeatRuntimeStatusVO {

    private String status;
    private String label;
    private boolean globalEnabled;
    private boolean sessionExists;
    private boolean manuallyPaused;
    private boolean gatePaused;

    public static AiSeatRuntimeStatusVO resolve(boolean globalEnabled,
                                                boolean sessionExists,
                                                boolean manuallyPaused,
                                                boolean gatePaused) {
        AiSeatRuntimeStatusVO result = new AiSeatRuntimeStatusVO();
        result.globalEnabled = globalEnabled;
        result.sessionExists = sessionExists;
        result.manuallyPaused = manuallyPaused;
        result.gatePaused = gatePaused;
        if (!globalEnabled) {
            result.status = "CLOSED";
            result.label = "AI坐席已关闭";
        } else if (manuallyPaused || gatePaused) {
            result.status = "PAUSED";
            result.label = "当前会话已暂停";
        } else if (sessionExists) {
            result.status = "RUNNING";
            result.label = "当前会话运行中";
        } else {
            result.status = "WAITING";
            result.label = "等待建立当前会话";
        }
        return result;
    }
}
