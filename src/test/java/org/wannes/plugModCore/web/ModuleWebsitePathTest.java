package org.wannes.plugModCore.web;
import org.junit.Test;
import org.wannes.plugModCore.module.*;
import java.io.File;
import static org.junit.Assert.*;
/** The local HTTP server and authenticated backend agent use the same entry-folder rules. */
public class ModuleWebsitePathTest {
 @Test public void nestedModuleWebsiteAssetsRetainTheirConfiguredEntryFolder(){
  var module=new ModuleContainer(new File("fixture.jar"));var info=new ModuleInfo();info.websiteEntry="web/editor/index.html";module.setInfo(info);
  assertEquals("web/editor/index.html",WebServer.resolveModuleWebsiteEntry(module,""));
  assertEquals("web/editor/app.js",WebServer.resolveModuleWebsiteEntry(module,"app.js"));
  assertEquals("web/editor/assets/icon.svg",WebServer.resolveModuleWebsiteEntry(module,"assets/icon.svg"));
  assertEquals("web/editor/index.html",WebServer.resolveModuleWebsiteEntry(module,"web/editor/index.html"));
 }
 @Test public void legacyWebsiteWithoutMetadataUsesItsExistingDefault(){assertEquals("web/index.html",WebServer.resolveModuleWebsiteEntry(new ModuleContainer(new File("fixture.jar")),""));}
}
