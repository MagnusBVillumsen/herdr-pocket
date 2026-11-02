{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      utils,
    }:
    utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      rec {
        packages.herdr-pocket = pkgs.rustPlatform.buildRustPackage {
          pname = "herdr-pocket";
          version = "0.1.0";
          # The phone app in app/ has its own flake; leave it out of the server build.
          src = pkgs.lib.cleanSourceWith {
            src = pkgs.lib.cleanSource ./.;
            filter = path: _: !(pkgs.lib.hasPrefix (toString ./app) path);
          };
          cargoLock.lockFile = ./Cargo.lock;
          # A plugin directory for `herdr plugin link`, with the binary where
          # the manifest expects the build output.
          postInstall = ''
            plugin=$out/share/herdr-pocket/plugin
            mkdir -p $plugin/target/release
            sed '/^\[\[build\]\]/,/^$/d' herdr-plugin.toml > $plugin/herdr-plugin.toml
            ln -s $out/bin/herdr-pocket $plugin/target/release/herdr-pocket
          '';
        };
        packages.default = packages.herdr-pocket;

        devShells.default = pkgs.mkShell {
          nativeBuildInputs = with pkgs; [
            cargo
            rustc
            clippy
            rustfmt
          ];
        };
      }
    );
}
