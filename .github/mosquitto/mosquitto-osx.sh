brew install mosquitto
rm /usr/local/etc/mosquitto/mosquitto.conf
cp "${GITHUB_WORKSPACE}/.github/mosquitto/ditchoom_mqtt_test_server.conf" /usr/local/etc/mosquitto/mosquitto.conf
chmod 755 /usr/local/etc/mosquitto/mosquitto.conf
/usr/local/opt/mosquitto/sbin/mosquitto -d -c "${GITHUB_WORKSPACE}/.github/mosquitto/ditchoom_mqtt_test_server.conf"
