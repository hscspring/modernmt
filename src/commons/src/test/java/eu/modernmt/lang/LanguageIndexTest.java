package eu.modernmt.lang;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Created by davide on 10/02/18.
 */
public class LanguageIndexTest {

    private static Language l(String s) {
        return Language.fromString(s);
    }

    private static LanguagePattern p(String s) {
        return LanguagePattern.parse(s);
    }

    private static LanguageDirection lp(String s) {
        String[] parts = s.split("\\s+");
        return new LanguageDirection(Language.fromString(parts[0]), Language.fromString(parts[1]));
    }

    @Test
    public void simpleLanguageMonodirectional() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en it"))
                .build();

        assertEquals(lp("en it"), index.map(lp("en it")));
        assertEquals(lp("en it"), index.map(lp("en it-IT")));
        assertEquals(lp("en it"), index.map(lp("en-US it")));
        assertEquals(lp("en it"), index.map(lp("en-US it-IT")));
        assertEquals(lp("en it"), index.map(lp("en-US it-IT-x-Custom")));

        assertNull(index.map(lp("it en")));
        assertNull(index.map(lp("it-IT en")));
        assertNull(index.map(lp("it en-US")));
        assertNull(index.map(lp("it-IT en-US")));
    }

    @Test
    public void simpleLanguageBidirectional() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en fr"))
                .add(lp("fr en"))
                .build();

        assertEquals(lp("en fr"), index.map(lp("en fr")));
        assertEquals(lp("en fr"), index.map(lp("en fr-FR")));
        assertEquals(lp("en fr"), index.map(lp("en-GB fr")));
        assertEquals(lp("en fr"), index.map(lp("en-GB fr-FR")));

        assertEquals(lp("fr en"), index.map(lp("fr en")));
        assertEquals(lp("fr en"), index.map(lp("fr-FR en")));
        assertEquals(lp("fr en"), index.map(lp("fr en-GB")));
        assertEquals(lp("fr en"), index.map(lp("fr-FR en-GB")));
    }

    @Test
    public void ruledLanguageMonodirectional() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en es-ES"))
                .add(lp("en es-MX"))
                .addRule(p("es * *"), l("es-MX"))
                .build();

        assertEquals(lp("en es-MX"), index.map(lp("en es")));
        assertEquals(lp("en es-ES"), index.map(lp("en es-ES")));
        assertEquals(lp("en es-MX"), index.map(lp("en es-MX")));
        assertEquals(lp("en es-MX"), index.map(lp("en es-CO")));
        assertEquals(lp("en es-MX"), index.map(lp("en-US es-CO")));
        assertEquals(lp("en es-MX"), index.map(lp("en-US-x-Custom es-CO-x-Custom")));

        assertNull(index.map(lp("es en")));
        assertNull(index.map(lp("es-ES en")));
        assertNull(index.map(lp("es-MX en")));
        assertNull(index.map(lp("es-CO en")));
        assertNull(index.map(lp("es-CO en-US")));
    }

    @Test
    public void ruledLanguageBidirectional() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en zh-CN"))
                .add(lp("en zh-TW"))
                .add(lp("zh en"))
                .addRule(p("zh * HK"), l("zh-TW"))
                .addRule(p("zh * *"), l("zh-CN"))
                .build();

        assertEquals(lp("en zh-CN"), index.map(lp("en zh")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-CN")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-Hans-CN")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-XX")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-TW")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-HK")));
        assertEquals(lp("en zh-TW"), index.map(lp("en-US zh-HK")));

        assertEquals(lp("zh en"), index.map(lp("zh en")));
        assertEquals(lp("zh en"), index.map(lp("zh-CN en")));
        assertEquals(lp("zh en"), index.map(lp("zh-XX en")));
        assertEquals(lp("zh en"), index.map(lp("zh-TW en")));
        assertEquals(lp("zh en"), index.map(lp("zh-HK en")));
        assertEquals(lp("zh en"), index.map(lp("zh-HK en-US")));
        assertEquals(lp("zh en"), index.map(lp("zh-Hans-HK en-Latn-US")));
    }

    @Test
    public void ruledLanguageWithScript() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en sr-Latn"))
                .add(lp("en sr-Cyrl"))
                .addRule(p("sr * *"), l("sr-Latn"))
                .build();

        assertEquals(lp("en sr-Latn"), index.map(lp("en sr")));
        assertEquals(lp("en sr-Latn"), index.map(lp("en sr-RS")));

        assertEquals(lp("en sr-Latn"), index.map(lp("en sr-Latn")));
        assertEquals(lp("en sr-Latn"), index.map(lp("en sr-Latn-RS")));
        assertEquals(lp("en sr-Cyrl"), index.map(lp("en sr-Cyrl")));
        assertEquals(lp("en sr-Cyrl"), index.map(lp("en sr-Cyrl-RS")));
        assertEquals(lp("en sr-Cyrl"), index.map(lp("en sr-Cyrl-RS-x-Custom")));
    }

    @Test
    public void inconsistentLanguageDialectSupport() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en ru-AA"))
                .add(lp("en ru-BB"))
                .add(lp("es ru"))
                .build();

        // If a target language is supported in a language pair,
        // the same target language could be not supported in all language pairs.
        assertEquals(lp("en ru-AA"), index.map(lp("en ru-AA")));
        assertEquals(lp("en ru-BB"), index.map(lp("en ru-BB")));
        assertEquals(lp("es ru"), index.map(lp("es ru-AA")));
        assertEquals(lp("es ru"), index.map(lp("es ru-BB")));
    }

    @Test
    public void complexIndex() {
        LanguageIndex index = new LanguageIndex.Builder()
                .add(lp("en it")).add(lp("it en"))
                .add(lp("en pl"))
                .add(lp("en es-ES")).add(lp("en es-419")).add(lp("es en"))
                .add(lp("en pt-PT")).add(lp("en pt-BR")).add(lp("pt en"))
                .add(lp("en zh-TW")).add(lp("en zh-CN")).add(lp("zh en"))

                .addRule(p("es * NULL"), l("es-ES"))
                .addRule(p("es * +"), l("es-419"))

                .addRule(p("pt * NULL"), l("pt-PT"))

                .addRule(p("zh Hans *"), l("zh-CN"))
                .addRule(p("zh Hant *"), l("zh-TW"))
                .addRule(p("zh * SG"), l("zh-CN"))
                .addRule(p("zh * HK"), l("zh-TW"))
                .addRule(p("zh * MO"), l("zh-TW"))
                .addRule(p("zh * *"), l("zh-CN"))

                .build();

        assertEquals(lp("en it"), index.map(lp("en it")));
        assertEquals(lp("en it"), index.map(lp("en it-IT")));
        assertEquals(lp("it en"), index.map(lp("it en")));
        assertEquals(lp("it en"), index.map(lp("it-IT en-US")));

        assertEquals(lp("en pl"), index.map(lp("en pl")));
        assertEquals(lp("en pl"), index.map(lp("en pl-PL")));
        assertNull(index.map(lp("pl en")));

        assertEquals(lp("en es-ES"), index.map(lp("en es")));
        assertEquals(lp("en es-ES"), index.map(lp("en es-x-mo-SDL")));
        assertEquals(lp("en es-ES"), index.map(lp("en es-ES")));
        assertEquals(lp("en es-419"), index.map(lp("en es-MX")));
        assertEquals(lp("en es-419"), index.map(lp("en es-CO")));
        assertEquals(lp("en es-419"), index.map(lp("en es-XX")));

        assertEquals(lp("en pt-PT"), index.map(lp("en pt")));
        assertEquals(lp("en pt-PT"), index.map(lp("en pt-PT")));
        assertEquals(lp("en pt-BR"), index.map(lp("en pt-BR")));

        assertEquals(lp("en zh-CN"), index.map(lp("en zh-Hans")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-Hans-TW")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-Hans-CN")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-Hant")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-Hant-TW")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-Hant-CN")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-SG")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-HK")));
        assertEquals(lp("en zh-TW"), index.map(lp("en zh-MO")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh")));
        assertEquals(lp("en zh-CN"), index.map(lp("en zh-Unkn")));
    }
}
