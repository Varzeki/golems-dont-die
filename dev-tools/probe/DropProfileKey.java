import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Removes one key's line from a RuneLite profile, byte for byte otherwise.
 *
 * <p>Line endings, escapes and every other key are left exactly as they were. Refuses to
 * write unless the key occurs exactly once.
 *
 *   java DropProfileKey <profile> <key>
 */
public class DropProfileKey
{
	public static void main(String[] args) throws Exception
	{
		Path path = Paths.get(args[0]);
		String text = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
		String prefix = "\n" + args[1] + "=";
		int first = text.indexOf(prefix);
		if (first < 0 || text.indexOf(prefix, first + 1) >= 0)
		{
			System.out.println("found " + (first < 0 ? 0 : "more than one") + " line(s) for " + args[1] + "; nothing written");
			System.exit(1);
		}
		int start = first + 1;
		int end = text.indexOf('\n', start);
		end = end < 0 ? text.length() : end + 1;
		Files.write(path, (text.substring(0, start) + text.substring(end)).getBytes(StandardCharsets.ISO_8859_1));
		System.out.println("removed " + (end - start) + " bytes for " + args[1]);
	}
}
