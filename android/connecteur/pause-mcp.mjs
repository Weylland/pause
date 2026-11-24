import { execFile } from "node:child_process";
import { readFile, stat, writeFile } from "node:fs/promises";
import { createConnection } from "node:net";
import { resolve } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const exec = promisify(execFile);
const configFile = fileURLToPath(new URL(".pause-mcp.json", import.meta.url));
const tokenPattern = /^[A-Za-z0-9_-]{22}$/;
const sections = ["pause", "poids", "calories"];
const protocolVersions = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
const maxBytes = 2 * 1024 * 1024;
const dayMs = 86400000;

const tool = {
  name: "lire_bilan_pause",
  description: "Lit le journal Pause uniquement quand l’utilisateur demande un bilan. "
    + "Par défaut, renvoie les 30 derniers jours du téléphone. "
    + "Une journée ou une mesure absente correspond à une donnée manquante. "
    + "Les calories sont les apports saisis dans le journal et peuvent être incomplets.",
  annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
  inputSchema: {
    type: "object",
    additionalProperties: false,
    properties: {
      date_debut: { type: "string", format: "date", description: "Premier jour inclus (AAAA-MM-JJ)." },
      date_fin: { type: "string", format: "date", description: "Dernier jour inclus (AAAA-MM-JJ)." },
      sections: {
        type: "array", minItems: 1, maxItems: 3, uniqueItems: true,
        items: { type: "string", enum: sections },
        description: "Sections demandées. Sans filtre, les trois sont incluses.",
      },
    },
  },
};

const instructions = "Utiliser lire_bilan_pause uniquement à la demande de l’utilisateur. "
  + "Distinguer les jours sans réponse des jours non tenus. "
  + "Présenter la couverture des pesées et du journal alimentaire.";

function dateNumber(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new Error("Date attendue au format AAAA-MM-JJ.");
  }
  const timestamp = Date.parse(value + "T00:00:00Z");
  if (!Number.isFinite(timestamp) || isoDate(timestamp) !== value) {
    throw new Error("Date de calendrier invalide.");
  }
  return timestamp;
}

function isoDate(timestamp) {
  return new Date(timestamp).toISOString().slice(0, 10);
}

const round = value => Math.round(value * 10) / 10;
const object = value => Boolean(value && typeof value === "object" && !Array.isArray(value));

function validateArgs(args) {
  if (!object(args) || Object.keys(args).some(key => !Object.hasOwn(tool.inputSchema.properties, key))) {
    throw new Error("Arguments de bilan invalides.");
  }
  if (args.date_debut !== undefined) dateNumber(args.date_debut);
  if (args.date_fin !== undefined) dateNumber(args.date_fin);
  if (args.sections !== undefined && (!Array.isArray(args.sections) || !args.sections.length
      || new Set(args.sections).size !== args.sections.length || args.sections.some(key => !sections.includes(key)))) {
    throw new Error("Sections valides : pause, poids, calories.");
  }
}

function report(snapshot, args, source) {
  if (!object(snapshot) || snapshot.schema !== "pause.journal.v1" || !Array.isArray(snapshot.jours)) {
    throw new Error("Le fichier ou le téléphone ne fournit pas un bilan Pause compatible.");
  }
  const last = dateNumber(snapshot.date_export);
  const end = args.date_fin === undefined ? last : dateNumber(args.date_fin);
  const start = args.date_debut === undefined ? end - 29 * dayMs : dateNumber(args.date_debut);
  if (start > end || end - start > 365 * dayMs) {
    throw new Error("Demande une période valide de 366 jours maximum.");
  }
  const requested = args.sections ?? sections;
  const summary = {};
  const days = [];
  let previousDate = "";
  const from = isoDate(start), to = isoDate(end);
  for (const row of snapshot.jours) {
    if (!object(row)) throw new Error("Entrée de journal invalide.");
    dateNumber(row.date);
    if (row.date <= previousDate) throw new Error("Dates du journal incohérentes.");
    previousDate = row.date;
    if (row.date < from || row.date > to || row.date > snapshot.date_export) continue;
    const day = { date: row.date };
    if (requested.includes("pause")) {
      if (row.pause === "tenu") day.pause = row.pause;
      else if (["non_tenu", "consommation"].includes(row.pause)) day.pause = "non_tenu";
    }
    if (requested.includes("poids") && Number.isFinite(row.poids_kg) && row.poids_kg > 0) day.poids_kg = row.poids_kg;
    if (requested.includes("calories") && Number.isInteger(row.aliments_notes) && row.aliments_notes > 0
        && Number.isFinite(row.calories_notees_kcal) && row.calories_notees_kcal >= 0) {
      day.calories_notees_kcal = row.calories_notees_kcal;
      day.aliments_notes = row.aliments_notes;
    }
    if (Object.keys(day).length > 1) days.push(day);
  }
  if (requested.includes("pause")) {
    const goal = snapshot.objectif_pause;
    if (object(goal)) {
      const firstExpected = Math.max(start, dateNumber(goal.debut));
      const lastExpected = Math.min(end, last, dateNumber(goal.fin_incluse));
      const due = Math.max(0, (lastExpected - firstExpected) / dayMs + 1);
      const duringGoal = days.filter(row => row.date >= goal.debut && row.date <= goal.fin_incluse);
      const kept = duringGoal.filter(row => row.pause === "tenu").length;
      const notKept = duringGoal.filter(row => row.pause === "non_tenu").length;
      summary.pause = {
        objectif: { debut: goal.debut, fin_incluse: goal.fin_incluse, duree_jours: goal.duree_jours },
        jours_ecoules_de_l_objectif_sur_la_periode: due,
        jours_tenus: kept,
        jours_non_tenus: notKept,
        jours_sans_reponse: Math.max(0, due - kept - notKept),
      };
    } else {
      summary.pause = { objectif: null };
    }
  }
  if (requested.includes("poids")) {
    const weights = days.filter(row => row.poids_kg !== undefined);
    summary.poids = { nombre_de_pesees: weights.length };
    if (weights.length) {
      const first = weights[0], latest = weights.at(-1);
      summary.poids.premiere_pesee = { date: first.date, poids_kg: first.poids_kg };
      summary.poids.derniere_pesee = { date: latest.date, poids_kg: latest.poids_kg };
      if (weights.length > 1) summary.poids.variation_kg = round(latest.poids_kg - first.poids_kg);
    }
  }
  if (requested.includes("calories")) {
    const logged = days.filter(row => row.calories_notees_kcal !== undefined);
    summary.calories = { jours_avec_saisie: logged.length };
    if (logged.length) {
      const total = logged.reduce((sum, row) => sum + row.calories_notees_kcal, 0);
      summary.calories.total_note_kcal = round(total);
      summary.calories.moyenne_notee_par_jour_avec_saisie_kcal = round(total / logged.length);
    }
  }
  return {
    source,
    date_des_donnees: snapshot.date_export,
    fuseau_du_telephone: typeof snapshot.fuseau === "string" ? snapshot.fuseau : "inconnu",
    periode: { debut: from, fin: to },
    resume: summary,
    jours_renseignes: days,
  };
}

function readPhone(port, token) {
  return new Promise((resolveResponse, rejectResponse) => {
    const socket = createConnection({ port, host: "127.0.0.1" });
    const chunks = [];
    let size = 0;
    socket.setTimeout(5000, () => socket.destroy(new Error("Le téléphone ne répond pas.")));
    socket.once("connect", () => socket.write(token + "\n"));
    socket.on("data", chunk => {
      size += chunk.length;
      if (size > maxBytes) {
        socket.destroy(new Error("Bilan trop volumineux."));
        return;
      }
      chunks.push(chunk);
    });
    socket.once("end", () => {
      if (!size) rejectResponse(new Error("Ouvre l’accès dans l’onglet Bilan IA du téléphone."));
      else {
        try {
          const result = JSON.parse(Buffer.concat(chunks).toString("utf8"));
          if (result?.error) rejectResponse(new Error("Code refusé. Réassocie le téléphone dans le terminal."));
          else resolveResponse(result);
        } catch {
          rejectResponse(new Error("Réponse du téléphone illisible."));
        }
      }
    });
    socket.once("error", rejectResponse);
  });
}

async function snapshotFromUsb() {
  let token;
  try {
    token = JSON.parse(await readFile(configFile, "utf8")).token;
  } catch {
    throw new Error("Associe d’abord ton téléphone avec la commande « node pause-mcp.mjs associer ».");
  }
  if (!tokenPattern.test(token)) throw new Error("Le code d’association enregistré est invalide.");
  const adb = process.env.PAUSE_ADB || "adb";
  const prefix = process.env.ANDROID_SERIAL ? ["-s", process.env.ANDROID_SERIAL] : [];
  const adbRun = args => exec(adb, [...prefix, ...args], { timeout: 5000, windowsHide: true });
  let port;
  try {
    const { stdout } = await adbRun(["forward", "tcp:0", "localabstract:pause_journal_mcp_v1"]);
    port = stdout.trim();
  } catch (error) {
    if (error.code === "ENOENT") throw new Error("ADB est introuvable. Ajoute Platform-Tools au PATH.");
    throw new Error("Téléphone non accessible. Vérifie le câble USB, le débogage USB et l’autorisation sur Android.");
  }
  if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    throw new Error("ADB n’a pas attribué de port valide.");
  }
  try {
    return await readPhone(Number(port), token);
  } catch (error) {
    if (error.code) throw new Error("Ouvre l’accès dans Bilan IA et garde cet écran au premier plan.");
    throw error;
  } finally {
    await adbRun(["forward", "--remove", "tcp:" + port]).catch(() => {});
  }
}

async function snapshotFromFile(path) {
  try {
    const metadata = await stat(path);
    if (!metadata.isFile() || metadata.size > maxBytes) throw new Error();
    const text = await readFile(path, "utf8");
    if (Buffer.byteLength(text) > maxBytes) throw new Error();
    return JSON.parse(text.replace(/^\uFEFF/, ""));
  } catch {
    throw new Error("Le fichier de bilan configuré est introuvable, trop volumineux ou illisible.");
  }
}

async function associate() {
  const console = createInterface({ input: process.stdin, output: process.stderr });
  try {
    const token = (await new Promise(yes => console.question("Code affiché dans Bilan IA : ", yes))).trim();
    if (!tokenPattern.test(token)) throw new Error("Le code doit contenir 22 caractères.");
    await writeFile(configFile, JSON.stringify({ token }) + "\n", { mode: 0o600 });
    process.stderr.write("Association enregistrée. Ouvre l’accès dans Bilan IA pour chaque bilan.\n");
  } finally {
    console.close();
  }
}

function respond(id, result) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n");
}

function fail(id, code, message) {
  process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }) + "\n");
}

async function serve(file) {
  let initialized = false;
  const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
  for await (const line of input) {
    let request;
    try {
      request = JSON.parse(line);
    } catch {
      fail(null, -32700, "JSON invalide.");
      continue;
    }
    if (!object(request) || request.jsonrpc !== "2.0" || typeof request.method !== "string") {
      fail(request?.id ?? null, -32600, "Requête invalide.");
      continue;
    }
    const { id, method, params } = request;
    if (!Object.hasOwn(request, "id")) continue;
    if (method === "initialize") {
      const version = protocolVersions.includes(params?.protocolVersion) ? params.protocolVersion : protocolVersions[0];
      initialized = true;
      respond(id, { protocolVersion: version, capabilities: { tools: {} }, serverInfo: { name: "pause", version: "1.0.1" }, instructions });
    } else if (method === "ping") {
      respond(id, {});
    } else if (!initialized) {
      fail(id, -32002, "Session MCP non initialisée.");
    } else if (method === "tools/list") {
      respond(id, { tools: [tool] });
    } else if (method === "tools/call") {
      if (params?.name !== tool.name) {
        fail(id, -32602, "Outil inconnu.");
        continue;
      }
      try {
        const args = params.arguments ?? {};
        validateArgs(args);
        const snapshot = file ? await snapshotFromFile(file) : await snapshotFromUsb();
        const result = report(snapshot, args, file ? "fichier_exporte" : "telephone_usb");
        respond(id, { content: [{ type: "text", text: JSON.stringify(result) }] });
      } catch (error) {
        respond(id, { isError: true, content: [{ type: "text", text: error.message }] });
      }
    } else {
      fail(id, -32601, "Méthode inconnue.");
    }
  }
}

const args = process.argv.slice(2);
try {
  if (args.length === 1 && args[0] === "associer") await associate();
  else if (args.length === 0) await serve();
  else if (args.length === 2 && args[0] === "--fichier") await serve(resolve(args[1]));
  else throw new Error("Usage : node pause-mcp.mjs [associer | --fichier chemin.json]");
} catch (error) {
  process.stderr.write(error.message + "\n");
  process.exitCode = 1;
}
