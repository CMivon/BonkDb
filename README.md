# BonkDb
## Concussive SQL!

BonkDb is your standard SQL with a little chaos sprinkled in.

## The Magic!
Why would I ever use BondDb? *BECAUSE* BonkDb can sprinkle a little magic into your otherwise boring life!

- Order By Chaos: ``SELECT * FROM CrazyTable ORDER BY Chaos``
  - Create a truely* Random Order
-  Immaculate  Vibes: ``SELECT chill FROM WhatATable WHERE vibes='Immaculate'``
    - Useless condition - always returns true. No Negative vibes here!  
- Panic: ``PANIC``
  - Will randomly corrupt and delete your data. Life would be too easy otherwise :)
- Revenge: ``SELECT * FROM AngryTable ORDER BY name``
  - Delete a folder from your BonkDb directory making you seek REVENGE!!!!!
  - *NOTE*: Use this at your own risk!
## Usage
BonkDb works right out of the box. No need to install any third party libraries
To Run:

`Java .\Cli.java`


1. Commands
```
.tables              list loaded tables and their columns
.load <name> <path>  load a table from a saved .tbl file
.save <name> [path]  save a table to a .tbl file (defaults to <name>.tbl)
.help                show this message
.exit / .quit        quit
```

2. SQL
   BonkDb should work with basic sql commands:
   - Select, Join, Group By, Where, Order By, Update, Insert, Create Table

## Future Improvements
BonkDb is still a Work in Progress. More bonks are yet to come!

Some future improvements involve improving the architecture to more closely match popular engines such as postgres.

Tables:
  Right now tables are stored in text files and fully loaded into memory

Indexes:
  Not supported right now. It would use a B Tree

Joins:
  Nested For loops are being used. Something such as a hash join would be preferred.

Query Plans:
  Not supported yet. This is important if we want a faster db

*Disclaimer: Not actually truly random :(
   
