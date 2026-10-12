cask "umbrix" do
  arch arm: "aarch64", intel: "x64"

  version "0.1.0"
  sha256 arm:   :no_check,
         intel: :no_check

  url "https://github.com/Umbrix/umbrix/releases/download/v#{version}/Umbrix-#{version}-macos-#{arch}.dmg"
  name "Umbrix"
  desc "Industrial-Grade GPU-Accelerated Log Analysis Workstation"
  homepage "https://github.com/Umbrix/umbrix"

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
