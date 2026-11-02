package dev.phoneagent.core.model

/** Runs [ChatModelContract] on [FakeChatModel] without a flaw, so the contract is known to pass. */
class FakeChatModelContractTest : ChatModelContract() {
    override fun subject(): Subject = FakeChatModel.subject()
}
