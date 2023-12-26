package me.infinity.ngrokcom;

import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Region;
import com.github.alexdlaird.ngrok.protocol.Tunnel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.List;

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
        if (System.getenv("DYNU_CLIENT_ID") != null) {
            try {
                // Wait for 10 seconds
                Thread.sleep(10000);

                // Grab Full Ngrok Address
                String ngrokAddress = Files.readString(Paths.get("ngrok.log"));
                ngrokAddress = ngrokAddress.split("tcp://")[1].split(" ")[0];

                // Grab Ngrok IP and Port
                String ngrokIp = ngrokAddress.split(":")[0];
                ngrokIp = InetAddress.getByName(ngrokIp).getHostAddress();
                int ngrokPort = Integer.parseInt(ngrokAddress.split(":")[1]);

                // Retrieve Dynu Keys
                String dynuClientId = System.getenv("DYNU_CLIENT_ID");
                String dynuSecret = System.getenv("DYNU_SECRET");
                String dynuFull = dynuClientId + ":" + dynuSecret;

                // Retrieve Dynu Token
                String dynuToken = curlGetRequest("https://api.dynu.com/v2/oauth2/token",
                        "accept: application/json",
                        "-u", dynuFull);
                dynuToken = dynuToken.split("\"")[3];

                // Get Domain Name + ID
                String domain = curlGetRequest("https://api.dynu.com/v2/dns",
                        "accept: application/json",
                        "Authorization: Bearer " + dynuToken);
                String domainId = domain.split("\"")[6];
                domainId = domainId.substring(1, domainId.length() - 1);
                String domainName = domain.split("\"")[9];

                // Get DNS Records of Domain ID
                String dnsRecords = curlGetRequest("https://api.dynu.com/v2/dns/" + domainId + "/record",
                        "accept: application/json",
                        "Authorization: Bearer " + dynuToken);
                String dnsesIp = dnsRecords.split("id")[2].split(",")[0].substring(3);
                String dnsesPort = dnsRecords.split("id")[3].split(",")[0].substring(3);

                // Update DNS for Ngrok IP
                String updateIpResponse = curlPostRequest("https://api.dynu.com/v2/dns/" + domainId + "/record/" + dnsesIp,
                        "accept: application/json",
                        "Authorization: Bearer " + dynuToken,
                        "Content-Type: application/json",
                        "{\"nodeName\":\"mcngrok\",\"recordType\":\"A\",\"ttl\":120,\"state\":true,\"group\":\"\",\"ipv4Address\":\"" + ngrokIp + "\"}");

                getLogger().info("Update IP Response: " + updateIpResponse);

                // Update DNS for Ngrok PORT
                String updatePortResponse = curlPostRequest("https://api.dynu.com/v2/dns/" + domainId + "/record/" + dnsesPort,
                        "accept: application/json",
                        "Authorization: Bearer " + dynuToken,
                        "Content-Type: application/json",
                        "{\"nodeName\":\"_minecraft._tcp\",\"recordType\":\"SRV\",\"ttl\":120,\"state\":true,\"group\":\"\",\"host\":\"mcngrok." + domainName + "\",\"priority\":10,\"weight\":5,\"port\":" + ngrokPort + "}");

                getLogger().info("Update Port Response: " + updatePortResponse);

                getLogger().info("Finished DNS Update!\nConnect to your Server Now at '" + domainName + "'");
            } catch (IOException | InterruptedException e) {
                getLogger().log(Level.SEVERE, "Error updating Dynu DNS", e);
            }
        }
    }

 // Helper method to perform a GET request using curl
    private String curlGetRequest(String url, String... headers) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("curl");
        command.add("--silent");
        command.add("-X");
        command.add("GET");
        command.add(url);
        command.addAll(Arrays.asList(headers));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    // Helper method to perform a POST request using curl
    private String curlPostRequest(String url, String... headersAndData) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("curl");
        command.add("--silent");
        command.add("-X");
        command.add("POST");
        command.add(url);
        command.addAll(Arrays.asList(headersAndData));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
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

    // Method to save the configuration
    private void saveConfigSilently() {
        try {
            saveDefaultConfig();
        } catch (Exception e) {
        	            getLogger().log(Level.SEVERE, "Error saving configuration", e);
        }
    }
}
