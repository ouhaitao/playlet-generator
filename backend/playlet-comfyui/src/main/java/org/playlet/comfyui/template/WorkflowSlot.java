package org.playlet.comfyui.template;

/**
 * 可选链接型输入槽位（sidecar 清单的一条）。
 *
 * @param name         槽位名（= 宿主输入名，重名时加宿主 id 前缀）
 * @param hostId       宿主节点 id（可选输入所在节点）
 * @param inputName    宿主上的输入名
 * @param sourceId     链接源节点 id
 * @param sourceOutput 链接源输出槽位
 * @param type         链接类型（IMAGE/MODEL/...）
 * @param sourceKind   source（源节点，无链接型输入）| chain（链节点，如 LoraLoader，需旁路）
 */
public record WorkflowSlot(
        String name,
        String hostId,
        String inputName,
        String sourceId,
        int sourceOutput,
        String type,
        String sourceKind
) {
}
