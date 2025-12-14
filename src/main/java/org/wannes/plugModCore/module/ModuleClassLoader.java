package org.wannes.plugModCore.module;

import java.net.URL;
import java.net.URLClassLoader;

public class ModuleClassLoader extends URLClassLoader {

    public ModuleClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, parent);
    }
}
