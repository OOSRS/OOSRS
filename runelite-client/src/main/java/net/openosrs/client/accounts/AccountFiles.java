package net.openosrs.client.accounts;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;

final class AccountFiles
{
	static void restrict(Path path, boolean directory) throws IOException
	{
		if (Files.isSymbolicLink(path))
		{
			throw new IOException("Account path must not be a symbolic link");
		}
		if (Files.getFileStore(path).supportsFileAttributeView("posix"))
		{
			Files.setPosixFilePermissions(path, directory
				? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
				: EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
		else
		{
			AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
			if (acl == null) throw new IOException("Account file permissions are unavailable");
			acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
				.setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
		}
	}

	static void write(Path target, byte[] bytes) throws IOException
	{
		Path temporary = Files.createTempFile(target.getParent(), ".account-", ".tmp");
		try
		{
			restrict(temporary, false);
			Files.write(temporary, bytes);
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		finally { Files.deleteIfExists(temporary); }
	}

	private AccountFiles() {}
}
