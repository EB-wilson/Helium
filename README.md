# Helium

> **点击[>> 这里 <<](README_zh_CN.md)查看中文文档**<br>
> **[РУССКИЙ ЯЗЫК](README_ru_RU.md)**

<!--suppress ALL -->
<img alt="mod icon" height="120" src="icon.png" width="120"/>

A Mindustry UI optimization mod that provides aesthetic enhancements and a more intuitive interface to improve gameplay experience.

### Core Features

Currently in early development with primary features including:

- **Default Dialog Background Blur (Frosted Glass Effect)**

  The Helium mod introduces an elegant blurred transparent background for in-game dialogs (currently dynamic frosted glass effect, with potential plans for static blur backgrounds if optimization demands arise)  

  ![Blur Effect](preview_imgs/en/blur-1.png)  
  ![Blur Effect](preview_imgs/en/blur-2.png)

- **In-Game Entity Information Components**

  Provides various UI elements for game entities including units and blocks:

  - **Health & Status Indicators**:

    Displays real-time status (health, shields) above units and entities. When shield stacks exceed max health, multi-layer shields with stack counters are shown.  
  
    ![Status Display](preview_imgs/en/statusDisplay.png)
  
  - **Attack Range Indicators**:

    Units and turrets now visualize their attack ranges as translucent areas. Processed boundaries emphasize combined team range contours to prevent visual clutter, with subtle pulsating animations and chromatic borders.  
  
    ![Attack Range](preview_imgs/en/attackRange.png)
  
  - **Functional Block Effect Range Indicators**:

    Devices like Mend Projectors, Overdrive Projectors, and unit repair stations display effect ranges with distinctive colors.  
  
    ![Effect Range](preview_imgs/en/effectRange.png)

  Hold the control hotkey (default: Left Alt key on keyboards, or a toolbar button on Android devices) to display detailed information such as unit weapon attack angles and entity details (since optimization panels may disable hover info on block placement panels, holding the hotkey allows targeted selection):

  ![Control Button](preview_imgs/en/control-button.png)

  All features can be configured via the quick settings panel to control visibility per team. Disabled teams will not display corresponding elements. Access the quick configuration tool via the panel button:

  ![Info Display Quick Config Entry](preview_imgs/en/quick-config-entry.png)

  Configuration panel:

  ![Quick Configuration Panel](preview_imgs/en/quick-config.png)

- **Enhanced Block Placement Panel**

  A refined block selection panel integrating a quick item bar and standardized tool buttons. The panel can be collapsed to show only the quick item bar and toolbar, minimizing HUD clutter.

  The quick item bar supports pagination and allows users to pin frequently used items for rapid access while the panel is collapsed.

  ![Placement Panel (Collapsed)](preview_imgs/en/placement-fold.png)  
  ![Placement Panel (Expanded)](preview_imgs/en/placement-unfold.png)

- **The better Mod Manager and Mod Browser**

  Reworked the Mods Manager and Mods Browser, now they looks more aesthetically pleasing and functional, you can normally open [Mods] page to open new Mod Manager when it enabled, now the mods manager looks like:

  ![mod管理器](preview_imgs/en/modManager.png)
  ![mod管理器](preview_imgs/en/modBrowser.png)

### Mod Configuration

Added **_\[Helium Config]_** entry in game settings, providing modular control over all UI modifications:

![Config Entry](preview_imgs/en/configEntry.png)  
![Configuration Interface](preview_imgs/en/configurePane.png)

### Development Roadmap

Current Priorities:
- [x] In-game information panel quick toggle controls
- [ ] Enhanced HUD elements (wave info panel, block selection bar)
- [x] (Partially complete) Improved quick-access item bar with smart recommendations
- [x] Standardized quick toolbar for better UI button placement
- [ ] Redesigned mod configuration interface

Future Plans:
- [ ] Static background blur implementation
- [ ] Overhauled game settings interface
- [ ] Quick Schematic panel
- [ ] Performance-optimized attack/effect range indicators (lower quality variant)
