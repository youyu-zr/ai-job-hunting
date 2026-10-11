package com.maple.ai.job.hunting.model.vo;

import lombok.Data;

import java.util.Date;

@Data
public class BetaAccessVO {

    private boolean permanent;
    private boolean activationEnabled;
    private boolean accessActive;
    private boolean betaActivated;
    private Date periodOfValidityEndTime;
}
