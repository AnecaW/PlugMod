package org.wannes.plugModCore.module;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ModuleInfo {

    /* Identiteit */
    public String id;
    public String name;
    public String description;
    public String version;

    public Instant createdAt;
    public Instant updatedAt;

    /* Auteur */
    public String authorName;
    public String authorId;
    public String authorEmail;
    public String authorWebsite;

    /* Technisch */
    public String mainClass;
    public int apiVersion;
    public String language;

    /* Data */
    public String dataFolder;
    public List<String> defaultFiles;

    /* Website */
    public boolean websiteEnabled;
    public String websiteEntry;
    public String websiteTitle;

    /* Verification */
    public String verificationMethod;
    public String verificationIssuer;
    public String verificationKeyId;
    public String verificationProofFile;
    public boolean allowIfUnverified;

    /* Loader status */
    public boolean valid = false;
    public String error = null;
}
