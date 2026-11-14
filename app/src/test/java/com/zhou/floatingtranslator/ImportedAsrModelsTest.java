package com.zhou.floatingtranslator;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ImportedAsrModelsTest {
    @Test public void userModelIsLocalAndIndependentFromBuiltinSelection() {
        Context app = RuntimeEnvironment.getApplication();
        String id = ImportedAsrModels.newId();
        try {
            ImportedAsrModels.register(app, id, "私有日语模型", OfflineAsrModelCatalog.SENSEVOICE);
            OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(app, id);
            assertNotNull(model);
            assertEquals(OfflineAsrModelCatalog.SENSEVOICE, model.runtimeModelId);
            assertEquals("参数量未验证", model.parameterText());
            assertTrue(model.name.contains("私有日语模型"));
            assertEquals("", model.url);
            assertTrue(OfflineAsrModelCatalog.all(app).stream()
                .anyMatch(item -> id.equals(item.id)));
            assertEquals(id, TranslationService.strictAsrMode(id));
            assertTrue(OfflineAsrModelCatalog.supportsLanguage(app, id, "ja"));
            assertFalse(OfflineAsrModelCatalog.supportsLanguage(app, id, "ms"));
            assertFalse(OfflineAsrModelCatalog.supportsMixedLanguages(app, id,
                Arrays.asList("en", "ms")));
        } finally {
            ImportedAsrModels.remove(app, id);
        }
        assertNull(OfflineAsrModelCatalog.find(app, id));
    }

    @Test public void importerChecksModelStructureAndDoesNotAcceptAnyOnnx() throws Exception {
        File dir = Files.createTempDirectory("asr_import_").toFile();
        try {
            Files.write(new File(dir, "model.int8.onnx").toPath(), new byte[]{1});
            Files.write(new File(dir, "tokens.txt").toPath(), new byte[]{2});
            OfflineModelStore.validateImportedPackage(dir, OfflineAsrModelCatalog.SENSEVOICE);
            try {
                OfflineModelStore.validateImportedPackage(dir, OfflineAsrModelCatalog.QWEN3_ASR);
                fail("Qwen requires frontend, encoder, decoder and tokenizer");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("缺少"));
            }
        } finally {
            for (File file : dir.listFiles()) file.delete();
            dir.delete();
        }
    }

    @Test public void importedPacksShowTemplateProvenanceNotFakeOfficialModel() {
        Context app = RuntimeEnvironment.getApplication();
        String id = ImportedAsrModels.newId();
        try {
            ImportedAsrModels.register(app, id, "手选的模型", OfflineAsrModelCatalog.WHISPER_SMALL);
            OfflineAsrModelCatalog.Model model = OfflineAsrModelCatalog.find(app, id);
            assertEquals("https://github.com/openai/whisper",
                ModelSourceLinks.officialModelUrl(model.runtimeModelId));
            assertTrue(ModelSourceLinks.packSourceUrl(model).isEmpty());
            assertFalse(model.requiresIndividualFiles());
        } finally {
            ImportedAsrModels.remove(app, id);
        }
    }
}
