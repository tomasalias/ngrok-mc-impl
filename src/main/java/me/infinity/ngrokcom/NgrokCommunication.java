package me.infinity.ngrokcom;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Region;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.spec.MessageCreateSpec;
import org.bukkit.plugin.java.JavaPlugin;

public final class NgrokCommunication extends JavaPlugin {

    private GatewayDiscordClient client;
    private NgrokClient ngrokClient;
    private String publicIp;
    private boolean discordModule;
    private boolean discordModuleStatus = false;
    private boolean dynuUpdateEnabled;

    @Override
    public void onEnable() {

        this.saveDefaultConfig();
        int ngrokPort = this.getServer().getPort();
        this.discordModule = this.getConfig().getBoolean("DISCORD_UPDATES.ENABLED");
        this.dynuUpdateEnabled = this.getConfig().getBoolean("DYNU_API.ENABLED");
        
        String ngrokAuthToken = this.getConfig().getString("NGROK_SETTINGS.AUTH_TOKEN");
        
        if (ngrokAuthToken == null || ngrokAuthToken.isEmpty()) {
            this.getLogger().warning("Ngrok authentication token is missing in the config. Shutting down...");
            this.setEnabled(false);
            return;
        }

        if (discordModule) {
        	String botToken = this.getConfig().getString("DISCORD_UPDATES.BOT_TOKEN");
            if (botToken == null || botToken.isEmpty()) {
                this.getLogger().warning("Bot token is missing in the config. Shutting down...");
                this.setEnabled(false);
                return;
            }

            this.client = DiscordClientBuilder.create(botToken)
                    .build()
                    .login()
                    .block();

            if (this.client != null) this.discordModuleStatus = true;
        }
        
        if (dynuUpdateEnabled) {
            updateDynuDNS();
        }

        final JavaNgrokConfig javaNgrokConfig = new JavaNgrokConfig.Builder()
        		.withAuthToken(this.getConfig().getString("NGROK_SETTINGS.AUTH_TOKEN"))
                .withRegion(Region.valueOf(Objects.requireNonNull(this.getConfig().getString("NGROK_SETTINGS.REGION")).toUpperCase()))
                .build();

        this.ngrokClient = new NgrokClient.Builder()
                .withJavaNgrokConfig(javaNgrokConfig)
                .build();

        final CreateTunnel createTunnel = new CreateTunnel.Builder()
                .withProto(Proto.TCP)
                .withAddr(ngrokPort) // Use the configured Ngrok port
                .build();

        final Tunnel tunnel = ngrokClient.connect(createTunnel);
        this.publicIp = tunnel.getPublicUrl().replace("tcp://", "");

        if (discordModuleStatus) {
            String updateMessage = this.getConfig().getString("DISCORD_UPDATES.UPDATE_MESSAGE");
            if (updateMessage != null && !updateMessage.isEmpty()) {
                client.getChannelById(Snowflake.of(Objects.requireNonNull(this.getConfig().getString("DISCORD_UPDATES.UPDATE_CHANNEL_ID"))))
                        .ofType(GuildMessageChannel.class)
                        .flatMap(guildMessageChannel -> guildMessageChannel.createMessage(MessageCreateSpec.builder()
                                .content(updateMessage.replace("%server_ip%", publicIp))
                                .build()
                        )).subscribe();
            } else {
                this.getLogger().warning("IP update message is missing in the config. Update message not sent.");
            }
        }

        this.getLogger().info("Listening server on port " + ngrokPort + ", IP: " + publicIp);
    }
    
    private void updateDynuDNS() {
        try {
            // Grab Full Ngrok Address
            String ngrokAddress = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/ngrok.log")))
                    .lines().findFirst().orElse("");
            ngrokAddress = ngrokAddress.substring(ngrokAddress.indexOf("tcp://") + 6, ngrokAddress.indexOf(" "));

            // Grab Ngrok IP and Port
            String ngrokIp = ngrokAddress.split(":")[0];
            String ngrokPort = ngrokAddress.split(":")[1];

            // Retrieve Dynu Keys
            String dynuClientId = getConfig().getString("DYNU_API.CLIENT_ID");
            String dynuSecret = getConfig().getString("DYNU_API.SECRET");
            String dynuFull = dynuClientId + ":" + dynuSecret;

            // Retrieve Dynu Token
            URL tokenUrl = new URL("https://api.dynu.com/v2/oauth2/token");
            HttpURLConnection tokenConnection = (HttpURLConnection) tokenUrl.openConnection();
            tokenConnection.setRequestMethod("GET");
            tokenConnection.setRequestProperty("Authorization", "Basic " + dynuFull);
            tokenConnection.setRequestProperty("Accept", "application/json");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(tokenConnection.getInputStream()))) {
                String response = reader.lines().reduce("", String::concat);
                String dynuToken = response.split("\"")[3];

                // Get Domain Name + ID
                URL domainUrl = new URL("https://api.dynu.com/v2/dns");
                HttpURLConnection domainConnection = (HttpURLConnection) domainUrl.openConnection();
                domainConnection.setRequestMethod("GET");
                domainConnection.setRequestProperty("Authorization", "Bearer " + dynuToken);
                domainConnection.setRequestProperty("Accept", "application/json");

                try (BufferedReader domainReader = new BufferedReader(new InputStreamReader(domainConnection.getInputStream()))) {
                    String domainResponse = domainReader.lines().reduce("", String::concat);
                    String domainId = domainResponse.split("\"")[6].replace("\"", "");

                    // Get DNS Records of Domain ID
                    URL dnsUrl = new URL("https://api.dynu.com/v2/dns/" + domainId + "/record");
                    HttpURLConnection dnsConnection = (HttpURLConnection) dnsUrl.openConnection();
                    dnsConnection.setRequestMethod("GET");
                    dnsConnection.setRequestProperty("Authorization", "Bearer " + dynuToken);
                    dnsConnection.setRequestProperty("Accept", "application/json");

                    try (BufferedReader dnsReader = new BufferedReader(new InputStreamReader(dnsConnection.getInputStream()))) {
                        String dnsResponse = dnsReader.lines().reduce("", String::concat);
                        String[] dnsRecords = dnsResponse.split("\"id\"");

                        String dnsIpId = dnsRecords[1].split(",")[0].replace(":", "").replace("\"", "");
                        String dnsPortId = dnsRecords[2].split(",")[0].replace(":", "").replace("\"", "");

                        // Update DNS for Ngrok IP
                        updateDynuRecord(domainId, dnsIpId, ngrokIp, "A");

                        // Update DNS for Ngrok Port
                        updateDynuRecord(domainId, dnsPortId, ngrokPort, "SRV");
                    }
                }
            }
        } catch (Exception e) {
            getLogger().severe("Error updating Dynu DNS: " + e.getMessage());
        }
    }

    private void updateDynuRecord(String domainId, String recordId, String value, String recordType) throws Exception {
        String apiUrl = "https://api.dynu.com/v2/dns/" + domainId + "/record/" + recordId;
        URL url = new URL(apiUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + getConfig().getString("DYNU_API.TOKEN"));
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        String jsonBody = "{\"nodeName\":\"mcngrok\",\"recordType\":\"" + recordType + "\",\"ttl\":120,\"state\":true,\"group\":\"\",\"ipv4Address\":\"" + value + "\"}";
        connection.getOutputStream().write(jsonBody.getBytes());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            // Handle response if needed
        }
    }

    @Override
    public void onDisable() {
        if (discordModule) {
        	String botToken = this.getConfig().getString("DISCORD_UPDATES.BOT_TOKEN");
            if (botToken == null || botToken.isEmpty()) {
            	saveDefaultConfig();
            } else {
            	saveConfigSilently();
            }
            }
        String ngrokAuthToken = this.getConfig().getString("NGROK_SETTINGS.AUTH_TOKEN");
        if (ngrokAuthToken == null || ngrokAuthToken.isEmpty()) {
        	saveDefaultConfig();
        } else {
        	saveConfigSilently();
        }

    }
    // Method to save the configuration without generating warnings
    private void saveConfigSilently() {
        try {
            // Temporarily store the original system output and error streams
            // to suppress warnings during configuration save
            System.setOut(null);
            System.setErr(null);

            // Save the default configuration
            saveDefaultConfig();

            // Restore the original output and error streams
            System.setOut(System.out);
            System.setErr(System.err);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
