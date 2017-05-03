package eu.modernmt.model.corpus.impl.parallel;

import eu.modernmt.io.*;
import eu.modernmt.model.corpus.Corpus;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Locale;

/**
 * Created by davide on 10/07/15.
 */
public class FileCorpus implements Corpus {

    private FileProxy file;
    private String name;
    private Locale language;

    private static String getNameFromFilename(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot < 0 ? filename : filename.substring(0, lastDot);
    }

    private static Locale getLangFromFilename(String filename) {
        int lastDot = filename.lastIndexOf('.');
        return lastDot < 0 ? Locale.getDefault() : Locale.forLanguageTag(filename.substring(lastDot + 1));
    }

    public FileCorpus(File file) {
        this(FileProxy.wrap(file), null, null);
    }

    public FileCorpus(File file, String name) {
        this(FileProxy.wrap(file), name, null);
    }

    public FileCorpus(File file, String name, Locale language) {
        this(FileProxy.wrap(file), name, language);
    }

    public FileCorpus(FileProxy file) {
        this(file, null);
    }

    public FileCorpus(FileProxy file, String name) {
        this(file, name, null);
    }

    public FileCorpus(FileProxy file, String name, Locale language) {
        this.file = file;
        this.name = (name == null ? getNameFromFilename(file.getFilename()) : name);
        this.language = (language == null ? getLangFromFilename(file.getFilename()) : language);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Locale getLanguage() {
        return language;
    }

    @Override
    public LineReader getContentReader() throws IOException {
        return new UnixLineReader(file.getInputStream(), DefaultCharset.get());
    }

    @Override
    public LineWriter getContentWriter(boolean append) throws IOException {
        return new UnixLineWriter(file.getOutputStream(append), DefaultCharset.get());
    }

    @Override
    public Reader getRawContentReader() throws IOException {
        return new InputStreamReader(file.getInputStream(), DefaultCharset.get());
    }

    @Override
    public String toString() {
        return name + "." + language;
    }

}
