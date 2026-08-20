package com.example.myagent.command.application.domain.model.command;

public sealed interface SourceReference permits SourceReference.Branch, SourceReference.PullRequest {

    record Branch(String name) implements SourceReference {
    }

    record PullRequest(long number) implements SourceReference {
    }
}
