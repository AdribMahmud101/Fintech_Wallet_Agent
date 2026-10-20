package app.fusion.wallet.chain

// Generated from spec/abi/IKavachiModule.json (frozen V4.1 ABI): topic0 -> event name
// for every KavachiModule event, so no module log ever shows as a raw hex topic.
internal object ModuleEventNames {
    val byTopic: Map<String, String> = mapOf(
        "0xb399a6bcffaf73c6ba7b848000f190d0438d4cce2a1a07fb750255e765273656" to "ActionApprovalRevoked", // ActionApprovalRevoked(bytes32)
        "0xde46f81a4e31d7a9957706e7494f62247337082c4cf1446a8603ff83df033ab5" to "ActionApproved", // ActionApproved(bytes32,uint256,uint256,uint64)
        "0xec3c5fb2a9f98228404a340d65a7da26d4bb846738b0d1f39b0de18f0a0154e8" to "ActionExecuted", // ActionExecuted(bytes32,uint256,bytes32,uint8,uint256,uint256,uint256,uint256,uint256,uint256,bytes32)
        "0x97c854f499aef695661e30437a03bb608a96556ee775d57648e7217d96c449cd" to "ActionQueued", // ActionQueued(bytes32,bytes32,uint256,uint64,uint64,bytes32)
        "0x1000f6bd1e72a87b9c03a3e183615200d3706dc37ddf713d023f6dd4359de9bc" to "EmergencyUnwind", // EmergencyUnwind(address,bytes32,uint256)
        "0x55b6c2047d256a41d48c1a2e922b59254cf977232846a54a6e2edf5f64d0c8a0" to "ObligationPaid", // ObligationPaid(bytes32,bytes32,address,uint256,uint256,bool)
        "0x988d78150e916977bdbfb462693f383a1159550e3ecac16cb66825e6d89e3faa" to "ObligationsCommitted", // ObligationsCommitted(uint256,bytes32,uint256,uint256,bytes32)
        "0x07132b2418d1ecb2df85ff8d532ce93647aca2015b8534584835fe449819c206" to "Paused", // Paused(address,uint256,bytes32,bytes32)
        "0xe711ab354836ceffbda6c5b3c0d49f63c3f32ac451c0dac7cf57b7dbd57cce77" to "PayeeAdded", // PayeeAdded(bytes32,address,bytes32,uint64)
        "0x10123141b2de6471fea077881ba66a03b4bf3bd2163219efffe33be3de0fa78b" to "PayeeCancelled", // PayeeCancelled(bytes32,address)
        "0x3c456492c8c986820e943888e5b553848495eee3d5fbe862ed80863cc05f014c" to "PolicyConfigured", // PolicyConfigured(uint256,bytes32,bytes32)
        "0xcfaa7589a2e63e8d4716f72cf92d6c9420732c4be8131796c8e8098fd0e56b8d" to "QueueCancelled", // QueueCancelled(bytes32,address,uint256)
        "0xc6a44fe45b404ee70ba2e9e993d9a170e8ac96ffed8e94c8fc74dccfbb73fa60" to "QueueExpiredEvent", // QueueExpiredEvent(bytes32,uint256)
        "0xec2ac4aee149a0f1e7d6eb21cca66f0820e938352c1562d019d457385134899c" to "QueueInvalidated", // QueueInvalidated(bytes32,bytes32,uint256)
        "0x701e8c24798b81c80b4c2f858addf55ba311fb73c1a805eb07ff6547e7795ff8" to "Resumed", // Resumed(uint256,bytes32,bytes32)
        "0x6305ce73ecaab085cd60b3c0f8bd6aa3a3d0ae48b83eb70b89ca3e30ae3ea037" to "SessionRevoked", // SessionRevoked(bytes32,address,uint256,bytes32)
        "0x9e7bf6b62fe08261aa8011a06647f61e4ef18db5a61634453e968a2f186d2a9a" to "SessionStarted", // SessionStarted(bytes32,address,uint256,uint64,uint256,bytes32)
        "0x67e543ee278462ca5e0508c6f4b553ed8f2aaa32af56bcfb7053ac77e4505ee3" to "UnwindLegFailed", // UnwindLegFailed(address,bytes)
    )
}
