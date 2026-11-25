using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pause;

internal sealed class DriveConnection
{
    internal const string Scope = "https://www.googleapis.com/auth/drive.appdata";
    private const string TokenEndpoint = "https://oauth2.googleapis.com/token";
    internal static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = false })
    { Timeout = TimeSpan.FromSeconds(35), MaxResponseContentBufferSize = 8_388_608 };
    private static string SessionFile => Path.Combine(JournalStore.DirectoryPath, "drive-windows.session");
    private Session? session;
    private string? access;
    private DateTimeOffset expires;
    internal bool Connected => session is not null;
    internal string? Account => session?.AccountId;

    private sealed record Session(string ClientId, string ClientSecret, string Refresh, string AccountId);

    internal DriveConnection()
    {
        if (!File.Exists(SessionFile)) return;
        try
        {
            byte[] bytes = ProtectedData.Unprotect(File.ReadAllBytes(SessionFile), null, DataProtectionScope.CurrentUser);
            session = JsonSerializer.Deserialize<Session>(bytes);
        }
        catch (Exception error) when (error is IOException or CryptographicException or JsonException or UnauthorizedAccessException) { }
    }

    internal async Task ConnectAsync(string? configPath, CancellationToken cancellation)
    {
        string? clientId = session?.ClientId, clientSecret = session?.ClientSecret;
        if (configPath is not null)
        {
            if (new FileInfo(configPath).Length > 16_384) throw new InvalidDataException("Fichier de connexion Google invalide.");
            JsonNode? installed = JsonNode.Parse(await File.ReadAllTextAsync(configPath, cancellation))?["installed"];
            clientId = installed?["client_id"]?.GetValue<string>();
            clientSecret = installed?["client_secret"]?.GetValue<string>();
        }
        if (clientId is null || !clientId.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal)
            || string.IsNullOrWhiteSpace(clientSecret)) throw new InvalidDataException("Ce fichier doit provenir d’un client Google pour ordinateur.");

        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromMinutes(3));
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var redirect = new Uri($"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/drive");
        string state = RandomToken(), verifier = RandomToken();
        string challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        string address = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline&prompt=consent"
            + $"&client_id={Uri.EscapeDataString(clientId)}&redirect_uri={Uri.EscapeDataString(redirect.AbsoluteUri)}"
            + $"&scope={Uri.EscapeDataString(Scope)}&state={state}&code_challenge={challenge}&code_challenge_method=S256";
        Process.Start(new ProcessStartInfo(address) { UseShellExecute = true });
        string code = (await ReadAuthorizationAsync(listener, redirect, state, deadline.Token))["code"]
            ?? throw new IOException("Connexion Google annulée.");
        JsonObject token = await RequestTokenAsync(clientId, clientSecret, new Dictionary<string, string>
        { ["grant_type"] = "authorization_code", ["code"] = code, ["code_verifier"] = verifier, ["redirect_uri"] = redirect.AbsoluteUri }, cancellation);
        string authorized = token["access_token"]?.GetValue<string>() ?? throw new InvalidDataException("Google n’a pas donné accès à Drive.");
        string refresh = token["refresh_token"]?.GetValue<string>() ?? throw new InvalidDataException("Google n’a pas autorisé la synchro automatique.");
        var files = new DriveFiles(authorized);
        string account = await files.AccountAsync(cancellation);
        if (configPath is null && account != session?.AccountId)
            throw new InvalidDataException("Choisis le compte Google déjà associé à ce PC.");
        session = new Session(clientId, clientSecret, refresh, account);
        CacheToken(token, authorized);
        Save();
    }

    internal async Task<(DriveFiles Files, string Account)> FilesAsync(CancellationToken cancellation)
    {
        Session config = session ?? throw new InvalidOperationException("Connecte d’abord Google Drive.");
        if (access is null || DateTimeOffset.UtcNow >= expires)
        {
            JsonObject token = await RequestTokenAsync(config.ClientId, config.ClientSecret, new Dictionary<string, string>
            { ["grant_type"] = "refresh_token", ["refresh_token"] = config.Refresh }, cancellation);
            string refreshed = token["access_token"]?.GetValue<string>() ?? throw new InvalidDataException("Accès Google Drive expiré.");
            CacheToken(token, refreshed);
            if (token["refresh_token"]?.GetValue<string>() is string replacement)
            {
                session = config with { Refresh = replacement };
                Save();
            }
        }
        return (new DriveFiles(access!), config.AccountId);
    }

    internal void Disconnect()
    {
        File.Delete(SessionFile);
        session = null;
        access = null;
    }

    private void Save()
    {
        byte[] clear = JsonSerializer.SerializeToUtf8Bytes(session);
        byte[] locked = ProtectedData.Protect(clear, null, DataProtectionScope.CurrentUser);
        string temp = SessionFile + ".tmp";
        File.WriteAllBytes(temp, locked);
        File.Move(temp, SessionFile, overwrite: true);
    }

    private static async Task<JsonObject> RequestTokenAsync(string clientId, string secret, Dictionary<string, string> parameters, CancellationToken cancellation)
    {
        parameters.Add("client_id", clientId);
        parameters.Add("client_secret", secret);
        using var payload = new FormUrlEncodedContent(parameters);
        using HttpResponseMessage response = await Http.PostAsync(TokenEndpoint, payload, cancellation);
        if (!response.IsSuccessStatusCode)
            throw new IOException("Google ne répond pas à la demande de connexion. Réessaie ou reconnecte ton compte Drive.");
        return JsonNode.Parse(await response.Content.ReadAsStringAsync(cancellation))?.AsObject()
            ?? throw new InvalidDataException("Réponse Google illisible.");
    }

    private void CacheToken(JsonObject reply, string token)
    {
        access = token;
        expires = DateTimeOffset.UtcNow.AddSeconds(Math.Clamp(reply["expires_in"]?.GetValue<int>() ?? 0, 0, 3600) - 60);
    }

    internal static string RandomToken() => Base64Url(RandomNumberGenerator.GetBytes(32));
    internal static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    internal static async Task<System.Collections.Specialized.NameValueCollection> ReadAuthorizationAsync(
        TcpListener listener, Uri redirect, string state, CancellationToken cancellation)
    {
        while (true)
        {
            using TcpClient incoming = await listener.AcceptTcpClientAsync(cancellation);
            await using NetworkStream transport = incoming.GetStream();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            using var reader = new StreamReader(transport, Encoding.ASCII, leaveOpen: true);
            string? line;
            try { line = await reader.ReadLineAsync(timeout.Token); }
            catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { continue; }
            if (line is null || line.Length > 8192) continue;
            string[] request = line.Split(' ');
            if (request.Length != 3 || request[0] != "GET" || !request[1].StartsWith(redirect.AbsolutePath + "?", StringComparison.Ordinal)
                || !Uri.TryCreate(redirect, request[1], out Uri? callback)) continue;
            var query = System.Web.HttpUtility.ParseQueryString(callback.Query);
            if (query["state"] is not string returned || !CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(returned), Encoding.UTF8.GetBytes(state))) continue;
            const string page = "<!doctype html><meta charset=utf-8><title>Fil des jours</title><p>Tu peux fermer cette page et revenir à Fil des jours.</p>";
            byte[] body = Encoding.UTF8.GetBytes(page);
            await transport.WriteAsync(Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"), cancellation);
            await transport.WriteAsync(body, cancellation);
            return query;
        }
    }
}
