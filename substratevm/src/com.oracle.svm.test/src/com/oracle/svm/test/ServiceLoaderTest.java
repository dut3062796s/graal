/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.test;

import java.util.ServiceLoader;

import org.junit.Assert;
import org.junit.Test;

public class ServiceLoaderTest {

    interface ServiceInterface {
    }

    /* Registered as a service. */
    public static class ServiceA implements ServiceInterface {
    }

    /* Registered as a service. */
    public static class ServiceB implements ServiceInterface {
    }

    /* NOT registered as a service. */
    public static class ServiceC implements ServiceInterface {
    }

    /* NOT registered as a service. */
    public static class ServiceD implements ServiceInterface {
    }

    @Test
    public void test01OwnService() {
        /* Make ServiceD reachable, but it is not registered via META-INF. */
        allocateServiceD();

        ServiceLoader<ServiceInterface> loader = ServiceLoader.load(ServiceInterface.class);

        int numFound = 0;
        boolean foundA = false;
        boolean foundB = false;
        boolean foundC = false;
        boolean foundD = false;

        for (ServiceInterface instance : loader) {
            numFound++;
            String name = instance.getClass().getSimpleName();
            foundA |= name.equals("ServiceA");
            foundB |= name.equals("ServiceB");
            foundC |= name.equals("ServiceC");
            foundD |= name.equals("ServiceD");
        }

        Assert.assertEquals(2, numFound);
        Assert.assertTrue(foundA);
        Assert.assertTrue(foundB);
        Assert.assertFalse(foundC);
        Assert.assertFalse(foundD);
    }

    private static Object allocateServiceD() {
        return new ServiceD();
    }

    @Test
    public void test02JDKService() {
        ServiceLoader<?> loader = ServiceLoader.load(java.nio.file.spi.FileSystemProvider.class);

        boolean foundZip = false;

        for (Object instance : loader) {
            String name = instance.getClass().getSimpleName();
            foundZip |= name.equals("ZipFileSystemProvider");
        }

        Assert.assertTrue(foundZip);
    }
}
