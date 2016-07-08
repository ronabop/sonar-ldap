/*
 * SonarQube LDAP Plugin
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.ldap.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import org.apache.directory.api.ldap.model.constants.SupportedSaslMechanisms;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.entry.DefaultModification;
import org.apache.directory.api.ldap.model.entry.ModificationOperation;
import org.apache.directory.api.ldap.model.ldif.LdifEntry;
import org.apache.directory.api.ldap.model.ldif.LdifReader;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.api.util.FileUtils;
import org.apache.directory.server.core.api.CoreSession;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.api.InstanceLayout;
import org.apache.directory.server.core.factory.DefaultDirectoryServiceFactory;
import org.apache.directory.server.core.jndi.CoreContextFactory;
import org.apache.directory.server.core.partition.impl.avl.AvlPartition;
import org.apache.directory.server.kerberos.kdc.KdcServer;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.ldap.handlers.sasl.MechanismHandler;
import org.apache.directory.server.ldap.handlers.sasl.cramMD5.CramMd5MechanismHandler;
import org.apache.directory.server.ldap.handlers.sasl.digestMD5.DigestMd5MechanismHandler;
import org.apache.directory.server.ldap.handlers.sasl.gssapi.GssapiMechanismHandler;
import org.apache.directory.server.ldap.handlers.sasl.plain.PlainMechanismHandler;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.server.protocol.shared.transport.UdpTransport;
import org.apache.directory.server.xdbm.impl.avl.AvlIndex;
import org.apache.mina.util.AvailablePortFinder;

import javax.annotation.WillClose;
import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.InitialLdapContext;
import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Map;

public class ApacheDS {

  private final String realm;
  private final String baseDn;

  public static ApacheDS start(String realm, String baseDn) throws Exception {
    return new ApacheDS(realm, baseDn)
        .startDirectoryService()
        .startLdapServer()
        .activateNis();
  }

  public void stop() throws Exception {
    // kdcServer.stop();
    ldapServer.stop();
    directoryService.shutdown();
  }

  public String getUrl() {
    return "ldap://localhost:" + ldapServer.getPort();
  }

  /**
   * Stream will be closed automatically.
   */
  public void importLdif(@WillClose InputStream is) throws Exception {
    Preconditions.checkState(directoryService.isStarted(), "Directory service not started");
    try {
      LdifReader entries = new LdifReader(is);
      CoreSession coreSession = directoryService.getAdminSession();
      for (LdifEntry ldifEntry : entries) {
        coreSession.add(new DefaultEntry(coreSession.getDirectoryService().getSchemaManager(), ldifEntry.getEntry()));
        System.out.println("Imported " + ldifEntry.getDn());
      }
    } finally {
      Closeables.closeQuietly(is);
    }
  }

  public void disableAnonymousAccess() {
    directoryService.setAllowAnonymousAccess(false);
  }

  public void enableAnonymousAccess() {
    directoryService.setAllowAnonymousAccess(true);
  }

  private DirectoryService directoryService;
  private final LdapServer ldapServer;
  private final KdcServer kdcServer;

  private ApacheDS(String realm, String baseDn) {
    this.realm = realm;
    this.baseDn = baseDn;
    ldapServer = new LdapServer();
    kdcServer = new KdcServer();
  }

  private ApacheDS startDirectoryService() throws Exception {
//    Preconditions.checkState(!directoryService.isStarted());

    DefaultDirectoryServiceFactory factory = new DefaultDirectoryServiceFactory();
    factory.init(realm);

    directoryService = factory.getDirectoryService();
    directoryService.getChangeLog().setEnabled(false);
    directoryService.setShutdownHookEnabled(false);
    directoryService.setAllowAnonymousAccess(true);

    File workDir = new File("target/ldap-work/" + realm);
    if (workDir.exists()) {
      FileUtils.deleteDirectory(workDir);
    }
    InstanceLayout instanceLayout = new InstanceLayout(workDir);
    directoryService.setInstanceLayout(instanceLayout);

    AvlPartition partition = new AvlPartition(directoryService.getSchemaManager());
    partition.setId("Test");
    partition.setSuffixDn(new Dn(directoryService.getSchemaManager(), baseDn));
    partition.setIndexedAttributes(Sets.newHashSet(
      new AvlIndex<>("ou"),
      new AvlIndex<>("uid"),
      new AvlIndex<>("dc"),
      new AvlIndex<>("objectClass")
    ));
    partition.initialize();
    directoryService.addPartition(partition);

    directoryService.shutdown();
    directoryService.startup();

    return this;
  }

  private ApacheDS startLdapServer() throws Exception {
    Preconditions.checkState(directoryService.isStarted());
    Preconditions.checkState(!ldapServer.isStarted());

    int port = AvailablePortFinder.getNextAvailable(1024);
    ldapServer.setTransports(new TcpTransport(port));
    ldapServer.setDirectoryService(directoryService);

    // Setup SASL mechanisms
    Map<String, MechanismHandler> mechanismHandlerMap = Maps.newHashMap();
    mechanismHandlerMap.put(SupportedSaslMechanisms.PLAIN, new PlainMechanismHandler());
    mechanismHandlerMap.put(SupportedSaslMechanisms.CRAM_MD5, new CramMd5MechanismHandler());
    mechanismHandlerMap.put(SupportedSaslMechanisms.DIGEST_MD5, new DigestMd5MechanismHandler());
    mechanismHandlerMap.put(SupportedSaslMechanisms.GSSAPI, new GssapiMechanismHandler());
    ldapServer.setSaslMechanismHandlers(mechanismHandlerMap);

    ldapServer.setSaslHost("localhost");
    ldapServer.setSaslRealms(Collections.singletonList(realm));
    // TODO ldapServer.setSaslPrincipal();
    // The base DN containing users that can be SASL authenticated.
    ldapServer.setSearchBaseDn(baseDn);

    ldapServer.start();

    return this;
  }

  @SuppressWarnings("unused")
  private ApacheDS startKerberos() throws Exception {
    Preconditions.checkState(ldapServer.isStarted());

    kdcServer.setDirectoryService(directoryService);
    // FIXME hard-coded ports
    kdcServer.setTransports(new TcpTransport(6088), new UdpTransport(6088));
    kdcServer.setEnabled(true);
//    kdcServer.setPrimaryRealm(realm);
    kdcServer.setSearchBaseDn(baseDn);
//    kdcServer.setKdcPrincipal("krbtgt/" + realm + "@" + baseDn);
    kdcServer.start();

    // -------------------------------------------------------------------
    // Enable the krb5kdc schema
    // -------------------------------------------------------------------

    Hashtable<String, Object> env = new Hashtable<String, Object>();
//    env.put(DirectoryService.JNDI_KEY, directoryService);
    env.put(Context.INITIAL_CONTEXT_FACTORY, CoreContextFactory.class.getName());
//    env.put(Context.PROVIDER_URL, ServerDNConstants.OU_SCHEMA_DN);
    InitialLdapContext schemaRoot = new InitialLdapContext(env, null);

    // check if krb5kdc is disabled
    Attributes krb5kdcAttrs = schemaRoot.getAttributes("cn=Krb5kdc");
    boolean isKrb5KdcDisabled = false;
    if (krb5kdcAttrs.get("m-disabled") != null) {
      isKrb5KdcDisabled = ((String) krb5kdcAttrs.get("m-disabled").get()).equalsIgnoreCase("TRUE");
    }

    // if krb5kdc is disabled then enable it
    if (isKrb5KdcDisabled) {
      Attribute disabled = new BasicAttribute("m-disabled");
      ModificationItem[] mods = new ModificationItem[] {new ModificationItem(DirContext.REMOVE_ATTRIBUTE, disabled)};
      schemaRoot.modifyAttributes("cn=Krb5kdc", mods);
    }
    return this;
  }

  /**
   * This seems to be required for objectClass posixGroup.
   */
  private ApacheDS activateNis() throws Exception {
    Preconditions.checkState(ldapServer.isStarted());
    directoryService.getAdminSession().modify(
      new Dn("cn=nis,ou=schema"),
      new DefaultModification(ModificationOperation.REPLACE_ATTRIBUTE, "m-disabled", "FALSE")
    );
    return this;
  }

}
