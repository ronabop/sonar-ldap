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
package org.sonar.plugins.ldap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import java.io.IOException;
import java.util.Properties;
import javax.annotation.Nullable;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.config.Settings;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

/**
 * @author Evgeny Mandrikov
 */
public class LdapContextFactory {

  private static final Logger LOG = Loggers.get(LdapContextFactory.class);

  private static final String DEFAULT_AUTHENTICATION = "simple";
  private static final String DEFAULT_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";
  private static final String DEFAULT_REFERRAL = "follow";

  @VisibleForTesting
  static final String GSSAPI_METHOD = "GSSAPI";

  @VisibleForTesting
  static final String DIGEST_MD5_METHOD = "DIGEST-MD5";

  @VisibleForTesting
  static final String CRAM_MD5_METHOD = "CRAM-MD5";

  /**
   * The Sun LDAP property used to enable connection pooling. This is used in the default implementation to enable
   * LDAP connection pooling.
   */
  private static final String SUN_CONNECTION_POOLING_PROPERTY = "com.sun.jndi.ldap.connect.pool";

  private static final String SASL_REALM_PROPERTY = "java.naming.security.sasl.realm";

  private final String providerUrl;
  private final boolean startTLS;
  private final String authentication;
  private final String factory;
  private final String username;
  private final String password;
  private final String realm;

  public LdapContextFactory(Settings settings, String settingsPrefix, String ldapUrl) {
    this.authentication = StringUtils.defaultString(settings.getString(settingsPrefix + ".authentication"), DEFAULT_AUTHENTICATION);
    this.factory = StringUtils.defaultString(settings.getString(settingsPrefix + ".contextFactoryClass"), DEFAULT_FACTORY);
    this.realm = settings.getString(settingsPrefix + ".realm");
    this.providerUrl = ldapUrl;
    this.startTLS = settings.getBoolean(settingsPrefix + ".StartTLS");
    this.username = settings.getString(settingsPrefix + ".bindDn");
    this.password = settings.getString(settingsPrefix + ".bindPassword");
  }

  /**
   * Returns {@code InitialDirContext} for Bind user.
   */
  public InitialDirContext createBindContext() throws NamingException {
    return createInitialDirContext(username, password, true);
  }

  /**
   * Returns {@code InitialDirContext} for specified user.
   * Note that pooling intentionally disabled by this method.
   */
  public InitialDirContext createUserContext(String principal, String credentials) throws NamingException {
    return createInitialDirContext(principal, credentials, false);
  }

  private InitialDirContext createInitialDirContext(String principal, String credentials, boolean pooling) throws NamingException {
    final InitialLdapContext ctx;
    if (startTLS) {
      ctx = new InitialLdapContext(getEnvironment(null, null, /* TODO(Godin): connection pooling requires proper TLS shutdown */false), null);
      // http://docs.oracle.com/javase/jndi/tutorial/ldap/ext/starttls.html
      StartTlsResponse tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
      try {
        tls.negotiate();
      } catch (IOException e) {
        NamingException ex = new NamingException("StartTLS failed");
        ex.initCause(e);
        throw ex;
      }
      ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, authentication);
      ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, principal);
      ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, credentials);
      // Explicitly initiate "bind" operation:
      ctx.reconnect(null);
    } else {
      ctx = new InitialLdapContext(getEnvironment(principal, credentials, pooling), null);
    }
    return ctx;
  }

  private Properties getEnvironment(@Nullable String principal, @Nullable String credentials, boolean pooling) {
    Properties env = new Properties();
    env.put(Context.SECURITY_AUTHENTICATION, authentication);
    if (realm != null) {
      env.put(SASL_REALM_PROPERTY, realm);
    }
    if (pooling) {
      // Enable connection pooling
      env.put(SUN_CONNECTION_POOLING_PROPERTY, "true");
    }
    env.put(Context.INITIAL_CONTEXT_FACTORY, factory);
    env.put(Context.PROVIDER_URL, providerUrl);
    env.put(Context.REFERRAL, DEFAULT_REFERRAL);
    if (principal != null) {
      env.put(Context.SECURITY_PRINCIPAL, principal);
    }
    // Note: debug is intentionally was placed here - in order to not expose password in log
    LOG.debug("Initializing LDAP context {}", env);
    if (credentials != null) {
      env.put(Context.SECURITY_CREDENTIALS, credentials);
    }
    return env;
  }

  public boolean isSasl() {
    return DIGEST_MD5_METHOD.equals(authentication) ||
      CRAM_MD5_METHOD.equals(authentication) ||
      GSSAPI_METHOD.equals(authentication);
  }

  public boolean isGssapi() {
    return GSSAPI_METHOD.equals(authentication);
  }

  /**
   * Tests connection.
   *
   * @throws IllegalStateException if unable to open connection
   */
  public void testConnection() {
    if (StringUtils.isBlank(username) && isSasl()) {
      throw new IllegalArgumentException("When using SASL - property ldap.bindDn is required");
    }
    try {
      createBindContext();
      LOG.info("Test LDAP connection on {}: OK", providerUrl);
    } catch (NamingException e) {
      LOG.info("Test LDAP connection: FAIL");
      throw new IllegalStateException("Unable to open LDAP connection", e);
    }
  }

  public String getProviderUrl() {
    return providerUrl;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
      .add("url", providerUrl)
      .add("authentication", authentication)
      .add("factory", factory)
      .add("bindDn", username)
      .add("realm", realm)
      .toString();
  }

}
