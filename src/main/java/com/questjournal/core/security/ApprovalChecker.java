package com.questjournal.core.security;

/** Answers whether an operator has approved a particular command reward (identified by fingerprint). */
public interface ApprovalChecker {
    boolean isApproved(String fingerprint);
}
