/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
*/
package org.bedework.dumprestore.prdump;

import org.bedework.base.exc.BedeworkException;
import org.bedework.base.response.Response;
import org.bedework.calfacade.BwCategory;
import org.bedework.calfacade.BwContact;
import org.bedework.calfacade.BwLocation;
import org.bedework.calsvci.CalSvcI;
import org.bedework.calsvci.DumpIntf;
import org.bedework.dumprestore.AliasInfo;
import org.bedework.dumprestore.Defs;
import org.bedework.dumprestore.dump.DumpGlobals;
import org.bedework.util.logging.BwLogger;
import org.bedework.util.logging.Logged;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

import static org.bedework.base.response.Response.Status;

/** Base class for dumping. Provides logging and useful methods.
 *
 * @author Mike Douglass   douglm @ bedework.edu
 * @version 4.0
 */
public class Dumper implements Logged {
  private final Deque<String> pathStack = new ArrayDeque<>();

  private final DumpGlobals globals;

  /* ===================================================================
   *                       Constructor
   * =================================================================== */

  /**
   * @param globals for dump
   */
  public Dumper(final DumpGlobals globals) {
    this.globals = globals;
  }
  
  protected void addLn(final String val) {
    globals.info.addLn(val);
  }

  /**
   * @return true if ok
   */
  boolean open() {
    pathStack.clear();
    pushPath(globals.getDirPath());

    if (globals.getDirPath() == null) {
      addLn("Null directory name");
      return false;
    }

    return true;
  }

  protected void dumpCategories(final boolean publick) {
    try {
      makeDir(Defs.categoriesDirName, false);

      final Collection<BwCategory> cats;
      if (publick) {
        cats = getSvc().getCategoriesHandler().getPublic();
      } else {
        cats = getSvc().getCategoriesHandler().get();
      }

      for (final BwCategory cat: cats) {
        incCount(DumpGlobals.categories);
        
        final File catFile = makeFile(cat.getWordVal() + ".xml");
        cat.dump(catFile);
      }
    } finally {
      popPath();
    }
  }

  protected void dumpLocations(final boolean publick) {
    try {
      makeDir(Defs.locationsDirName, false);

      final Collection<BwLocation> ents;
      if (publick) {
        ents = getSvc().getLocationsHandler().getPublic();
      } else {
        ents = getSvc().getLocationsHandler().get();
      }

      for (final BwLocation ent: ents) {
        incCount(DumpGlobals.locations);

        final File f = makeFile(ent.getUid() + ".xml");
        ent.dump(f);
      }
    } finally {
      popPath();
    }
  }

  protected void dumpContacts(final boolean publick) {
    try {
      makeDir(Defs.contactsDirName, false);

      final Collection<BwContact> ents;
      if (publick) {
        ents = getSvc().getContactsHandler().getPublic();
      } else {
        ents = getSvc().getContactsHandler().get();
      }

      for (final BwContact ent: ents) {
        incCount(DumpGlobals.contacts);

        final File f = makeFile(ent.getUid() + ".xml");
        ent.dump(f);
      }
    } finally {
      popPath();
    }
  }

  void incCount(final int index) {
    globals.counts[index]++;
  }
  
  protected void pushPath(final String val) {
    pathStack.push(val);
  }

  protected String topPath() {
    return pathStack.peek();
  }

  protected void popPath() {
    pathStack.pop();
  }
  
  /**
   */
  protected void close() {
  }

  /**
   * @return list of external subscriptions
   */
  public List<AliasInfo> getExternalSubs() {
    return globals.externalSubs;
  }

  /** Add a file using stack top as path
   * 
   * @param name filename
   * @return a File object
   */
  protected File makeFile(final String name) {
    try {
      String fname = name.replace('/', '_');
      
      for (int i = 0; i < 20; i++) {
        final Path p =
                FileSystems.getDefault().getPath(topPath(),
                                                 fname);

        final File f = p.toFile();

        if (f.exists()) {
          globals.info.addLn("Path " + p + " already exists.");
          fname = "dup_" + fname;
          continue;
        }
        
        if (!f.createNewFile()) {
          throw new BedeworkException("Unable to create file " + p);
        }

        return f;
      }
    } catch (final BedeworkException be) {
      throw be;
    } catch (final Throwable t) {
      throw new BedeworkException(t);
    }
    
    throw new BedeworkException("Unable to create file " + name);
  }

  /** Add a directory using stack top as path and pushes the new path
   *
   * @param name dirname
   * @return Response with status ok if created
   */
  protected Response<?> makeDir(final String name,
                                final boolean pushDup) {
    final var resp = new Response<>();

    try {
      final Path p =  FileSystems.getDefault().getPath(topPath(), name);

      final File f = p.toFile();

      if (f.exists()) {
        globals.info.addLn("Path " + p + " already exists.");
        
        if (pushDup) {
          pushPath(p.toString());
        }
        return resp.notOk(Status.exists,
                          "Path " + p + " already exists.");
      }

      if (!f.mkdirs()) {
        return resp.notOk(Status.failed,
                          "Unable to create directory " + p);
      }

      pushPath(p.toString());
      return resp;
    } catch (final Throwable t) {
      return resp.error(t);
    }
  }

  protected DumpIntf getDi() {
    return globals.di;
  }

  protected CalSvcI getSvc() {
    return globals.svci;
  }

  /* ====================================================================
   *                   Logged methods
   * ==================================================================== */

  private final BwLogger logger = new BwLogger();

  @Override
  public BwLogger getLogger() {
    if ((logger.getLoggedClass() == null) && (logger.getLoggedName() == null)) {
      logger.setLoggedClass(getClass());
    }

    return logger;
  }
}
