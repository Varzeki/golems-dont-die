import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Replaces an exact piece of text in a RuneLite profile, byte for byte otherwise.
 *
 * <p>For correcting one learned entry by hand without disturbing line endings, escapes or
 * any other key. Refuses to write unless the text occurs exactly once.
 *
 *   java ReplaceInProfile <profile> <find> <replace>
 */
public class ReplaceInProfile
{
	public static void main(String[] args) throws Exception
	{
		Path path = Paths.get(args[0]);
		String text = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
		int first = text.indexOf(args[1]);
		int last = text.lastIndexOf(args[1]);
		if (first < 0 || first != last)
		{
			System.out.println("found " + (first < 0 ? 0 : "more than one") + " occurrence(s); nothing written");
			System.exit(1);
		}
		Files.write(path, (text.substring(0, first) + args[2] + text.substring(first + args[1].length()))
			.getBytes(StandardCharsets.ISO_8859_1));
		System.out.println("replaced 1 occurrence");
	}
}
