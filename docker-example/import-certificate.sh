#!/bin/sh

set -e

until nc -z ldap-server 636; do
  echo "LDAP server is unavailable, sleeping..."
  sleep 1
done

echo "Importing LDAP server certificate..."
echo -n | openssl s_client -connect ldap-server:636 | sed -ne '/-BEGIN CERTIFICATE-/,/-END CERTIFICATE-/p' > ldapserver.pem
keytool -import -trustcacerts -alias ldapserver -file ldapserver.pem -keystore /etc/ssl/certs/java/cacerts -storepass changeit -noprompt

exec "$@"
