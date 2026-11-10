package org.playlet.comfyui.template;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入 CLI（以测试形式运行）：
 * mvn test -Dtest=WorkflowImportCliTest -Dimport.template=<全填版JSON路径>
 * 成功时在同目录生成 <name>.options.json sidecar。
 */
class WorkflowImportCliTest {

    @Test
    void importTemplate() throws Exception {
        String tpl = System.getProperty("import.template");
        Assumptions.assumeTrue(tpl != null && !tpl.isBlank(), "no -Dimport.template given");
        String baseUrl = System.getenv().getOrDefault("PLAYLET_COMFYUI_BASE_URL", "http://127.0.0.1:28070");

        NodeTypeResolver types = new ComfyNodeTypeResolver(baseUrl);
        WorkflowImporter importer = new WorkflowImporter(types);
        WorkflowImporter.ImportResult result = importer.importWorkflow(Path.of(tpl));

        assertTrue(result.problems().isEmpty(),
                "import problems: " + result.problems());
        assertTrue(Files.exists(result.sidecar()), "sidecar written: " + result.sidecar());
        System.out.println("[import-cli] OK slots="
                + result.slots().stream().map(s -> s.name() + "(" + s.sourceKind() + ")").toList()
                + " sidecar=" + result.sidecar());
    }
}
