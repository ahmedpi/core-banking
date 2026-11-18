package com.ahmed.corebanking.service;

import com.ahmed.corebanking.domain.Transaction;
import com.ahmed.corebanking.domain.event.FraudAssessment;
import com.ahmed.corebanking.domain.event.TransferCompletedEvent;

import java.util.List;

public interface FraudRiskEvaluator {
    FraudAssessment evaluate(TransferCompletedEvent event, List<Transaction> recentHourlyTransfers);
}