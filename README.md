# BonkDb
## Concussive SQL!

BonkDb is your standard SQL with a little chaos sprinkled in.

## The Magic!
Why would I ever use BondDb? *BECAUSE* BonkDb can sprinkle a little magic into your otherwise boring life!

- Order By Chaos: ``SELECT * FROM CrazyTable ORDER BY Chaos``
  - Create a truely* Random Order
-  Immaculate  Vibes: ``SELECT chill FROM WhatATable WHERE vibes='Immaculate'``
    - Useless condition - always returns true. No Negative vibes here!  
- Panic: ``.PANIC`` (cli command to be Implemented)
  - Will randomly corrupt your data. Life would be too easy otherwise :)
- Revenge: ``.REVENGE`` (cli command to be implemented)
  - Delete a folder from your BonkDb directory making you seek REVENGE!!!!!
  - *NOTE*: Use this at your own risk!
 

## Usage
BonkDb works right out of the box. No need to install any third party libraries
To Run:

```
javac -d out src/Art/Art.java src/DeleteFiles/DeleteFiles.java src/Storage/*.java src/Engine/*.java src/Main.java
java -cp out Main
```


1. Commands
```
.tables                list the tables and their columns
.export <name> [path]  write a table to a text file (defaults to <name>.tbl)
.import <name> [path]  create a table from a text file (defaults to <name>.tbl)
.panic                 (future upgrade -- not available yet)
.revenge               permanently delete a random folder from DeleteFiles/Sandbox/
.help                  show this message
.exit / .quit          quit
```

2. SQL
   BonkDb should work with basic sql commands:
   - Select, Join, Group By, Where, Order By, Update, Insert, Create Table

## Run the tests
Compile with the test folders included, then run either suite:

```
javac -d out src/Art/Art.java src/DeleteFiles/DeleteFiles.java src/Storage/*.java src/Engine/*.java src/Main.java test/Storage/*.java test/Engine/*.java
java -cp out Engine.RunEngineTests
java -cp out Storage.RunStorageTests
```
Please note: The tests do not rely on any third party suite. This will likely change in future versions

## Future Improvements
BonkDb is still a Work in Progress. More bonks are yet to come!

Some future improvements involve improving the architecture to more closely match popular engines.

Tables:
  Ideally tables should be cachable. Right not they are either directly accessed from the disk or stored entirely in memory

Indexes:
  Not supported right now. It would use a B Tree

Joins:
  Something such as a hash join would be preferred.

Query Plans:
  Not supported yet. This is important if we want a faster db

*Disclaimer: Not actually truly random :(
   
