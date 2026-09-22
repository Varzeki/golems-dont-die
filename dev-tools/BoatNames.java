import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Every Sailing boat object the client's own id tables name, so the larger hulls can be found. */
public class BoatNames
{
	public static void main(String[] args) throws Exception
	{
		String match = args.length > 0 ? args[0].toUpperCase() : "SAILING_BOAT";
		for (String owner : new String[]{"net.runelite.api.gameval.ObjectID", "net.runelite.api.gameval.NpcID"})
		{
			Class<?> type = Class.forName(owner);
			List<String> hits = new ArrayList<>();
			for (Field field : type.getDeclaredFields())
			{
				if (field.getType() == int.class && field.getName().contains(match))
				{
					hits.add(String.format("%8d  %s", field.getInt(null), field.getName()));
				}
			}
			java.util.Collections.sort(hits, (a, b) -> a.substring(10).compareTo(b.substring(10)));
			System.out.println("=== " + owner + ": " + hits.size() + " named " + match + " ===");
			for (String hit : hits)
			{
				System.out.println(hit);
			}
		}
	}
}
