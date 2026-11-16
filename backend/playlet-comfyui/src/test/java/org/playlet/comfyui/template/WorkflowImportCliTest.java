package org.playlet.comfyui.template;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入 CLI（以测试形式运行）：
 * mvn test -Dtest=WorkflowImportCliTest -Dimport.template=&lt;API格式JSON路径&gt;
 * [-Dimport.type=IMAGE|VIDEO] [-Dimport.model=模型名]
 * 原文保持不动，成功时在同目录生成 &lt;name&gt;.meta.json（不需要 ComfyUI 在线）。
 */
class WorkflowImportCliTest {

    @Test
    void importTemplate() throws Exception {
        String tpl = System.getProperty("import.template");
        Assumptions.assumeTrue(tpl != null && !tpl.isBlank(), "no -Dimport.template given");
        WorkflowType type = WorkflowType.valueOf(
                System.getProperty("import.type", WorkflowType.IMAGE.name()).trim().toUpperCase());
        String model = System.getProperty("import.model", "unknown");

        Path template = Path.of(tpl);
        WorkflowImporter.ImportResult result = new WorkflowImporter().importWorkflow(template, type, model);

        assertTrue(result.problems().isEmpty(), "import problems: " + result.problems());
        assertTrue(Files.exists(result.metaFile()), "meta written: " + result.metaFile());
        System.out.println("[import-cli] OK type=" + type + " model=" + model
                + " groups=" + result.meta().groups().size()
                + " fields=" + result.meta().fieldCount());
        for (WorkflowMeta.NodeGroup g : result.meta().groups()) {
            System.out.println("📦 [" + g.nodeId() + "] " + g.nodeTitle() + " (" + g.classType() + ")");
            for (WorkflowMeta.FieldParam f : g.fields()) {
                System.out.printf("   - %-22s [%-7s] path=%-28s = %s%n",
                        f.inputName(), f.kind(), f.jsonPath(), f.defaultValue());
            }
        }
    }
}
