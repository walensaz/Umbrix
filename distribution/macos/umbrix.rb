cask "umbrix" do
  version "1.0.1"
  sha256 :no_check

  depends_on arch: :arm64

  url "https://github.com/walensaz/Umbrix/releases/download/v#{version}/Umbrix-#{version}.dmg"
  name "Umbrix"
  desc "Off-heap desktop log and forensic analysis workstation"
  homepage "https://github.com/walensaz/Umbrix"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "Umbrix.app"

  binary "#{appdir}/Umbrix.app/Contents/MacOS/Umbrix", target: "umbrix"

  zap trash: [
    "~/.umbrix",
  ]
end
